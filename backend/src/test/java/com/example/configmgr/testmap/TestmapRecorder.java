package com.example.configmgr.testmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 倒排表录制器（设计稿 §3.2~§3.4）。
 *
 * <p>数据来源 = JaCoCo agent 的 {@code IAgent.getExecutionData(boolean)}：逐测试类取增量、类边界 reset，
 * 归并成「测试类 → 触达的生产类」倒排表，落 {@code backend/target/testmap/map.json}（O1：不落仓）。
 *
 * <p>三层口径：
 * <ul>
 *   <li><b>依赖定义</b>：出现在 execution data 即算依赖（M2）。本仓 JaCoCo 的 execution data 在 reset 后
 *       仍保留类条目（探针清零、条目不清），所以「是否本类触达」用两条判据：探针非零（本类执行过），
 *       或该类的 execution-data id 在本类边界内<b>首次</b>出现（本类装载过但没执行——静态初始化、
 *       反射目标、Spring 装配）。缺后者会把「只加载不执行」整片漏掉。</li>
 *   <li><b>名称归一</b>：VM 名 {@code com/example/Foo$Inner} → {@code com.example.Foo}（K2）。</li>
 *   <li><b>合成类排除</b>：Mockito / ByteBuddy / CGLIB / 动态代理，只留 {@code com.example.configmgr} 生产类。</li>
 * </ul>
 */
final class TestmapRecorder {

    static final String JACOCO_VERSION = "0.8.15";
    static final double MIN_KEY_OVERLAP = 0.95;

    private static final String APP_PREFIX = "com.example.configmgr";
    private static final String APP_PREFIX_VM = "com/example/configmgr";
    private static final Set<String> SYNTHETIC_MARKERS = Set.of(
            "$MockitoMock$", "$ByteBuddy$", "$$EnhancerBySpringCGLIB$$", "$EnhancerBySpringCGLIB$",
            "$Proxy", "$jdkProxy$", "$HibernateProxy$", "$FastClassBySpringCGLIB$");
    private static final Path OUT_DIR = Paths.get("target", "testmap");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** 测试类 FQN → 触达的生产类 FQN（跨线程并发写）。 */
    private static final Map<String, Set<String>> DEPS = new ConcurrentHashMap<>();
    /** 测试类 FQN → 探针命中过的生产类 FQN（只做统计与排障，不参与选择）。 */
    private static final Map<String, Set<String>> HITS = new ConcurrentHashMap<>();
    /** 测试类 FQN → 本类用例体耗时累计（ms）。只用于成本估算（L-101），不参与选择。 */
    private static final Map<String, Long> DURATIONS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    /** 已经出现过的 execution-data id：用于判定「本类区间内首次装载」（M2）。 */
    private static final Set<Long> SEEN_IDS = ConcurrentHashMap.newKeySet();

    private static volatile String lastTestClass;
    private static volatile boolean hookInstalled;
    private static volatile boolean finalized;
    private static volatile Throwable agentFailure;
    private static volatile Map<String, Path> prodSources;
    private static volatile Map<String, String> prodBlobs;
    private static volatile Set<String> concreteProd;
    private static volatile String headSha;
    private static volatile Double keyOverlap;
    private static volatile Double keyOverlapConcrete;
    private static volatile long startedAtMs;

    private TestmapRecorder() {
    }

    static boolean enabled() {
        return Boolean.getBoolean("testmap.enabled");
    }

    private static boolean fullMode() {
        return !"incremental".equalsIgnoreCase(System.getProperty("testmap.mode", "full"));
    }

    // ------------------------------------------------------------------ 录制

    static void record(String testClass, boolean reset) {
        if (testClass == null) {
            return;
        }
        if (startedAtMs == 0) {
            startedAtMs = System.currentTimeMillis();
        }
        testClass = normalizeTestKey(testClass);
        lastTestClass = testClass;
        ensureHook();
        List<ClassRec> recs;
        try {
            recs = snapshot(reset);
        } catch (Throwable t) {
            agentFailure = t;
            System.err.println("[testmap] 取执行数据失败，本类记无数据：" + testClass + " → " + t);
            return;
        }
        Map<String, Path> prod = prodSources();
        Set<String> deps = DEPS.computeIfAbsent(testClass, k -> ConcurrentHashMap.newKeySet());
        Set<String> hits = HITS.computeIfAbsent(testClass, k -> ConcurrentHashMap.newKeySet());
        for (ClassRec rec : recs) {
            if (!rec.vmName().startsWith(APP_PREFIX_VM)) {
                continue;
            }
            if (isSynthetic(rec.vmName())) {
                continue;
            }
            String fqn = normalize(rec.vmName());
            if (!prod.containsKey(fqn)) {
                continue; // 只记生产类；测试类/夹具/匿名内部类不参与倒排
            }
            if (rec.hit()) {
                deps.add(fqn);
                hits.add(fqn);
            } else if (SEEN_IDS.add(rec.id())) {
                // 本类区间内首次装载但没有探针命中：静态初始化/反射目标/装配面，仍算依赖（M2）
                deps.add(fqn);
            }
        }
    }

    static void markFailed(String testClass) {
        if (testClass != null) {
            FAILED.add(testClass);
        }
    }

    static void addDuration(String testClass, long ms) {
        if (testClass == null || ms < 0) {
            return;
        }
        DURATIONS.merge(normalizeTestKey(testClass), ms, Long::sum);
    }

    // ------------------------------------------------------- 不稳定集合重估（L-101）

    static int unstableRetireCleanRuns() {
        return Integer.getInteger("testmap.unstable.retire.cleanRuns", 2);
    }

    static long unstableMaxAgeMs() {
        return TimeUnit.DAYS.toMillis(Long.getLong("testmap.unstable.maxAgeDays", 7L));
    }

    /**
     * 不稳定集合重估（纯函数，L-101）。
     *
     * <p>背景：同 tree 连录两次只要有类不一致，那批类就进 {@code unstableTests} 并被选择器**永久**钉进冒烟集；
     * 一次高负载录制（33 类）就能把提交级从 107s 拖到 187s，而旧实现里这个集合还会在「不可比录制」时被
     * 静默清空——既没收敛，也不可解释。这里给出三条规则：
     *
     * <ul>
     *   <li><b>复现即续</b>：本次可比录制仍不一致的类 ⇒ {@code cleanStreak=0}，留在冒烟集；</li>
     *   <li><b>连续 N 次干净即移出</b>：曾在集合内、连续 {@code cleanRuns} 次可比录制未复现差异 ⇒ 移出
     *       （夜间录制即重估点）；</li>
     *   <li><b>时间窗兜底</b>：距最后一次复现超过 {@code maxAgeMs} ⇒ 过期移出（成本必须随时间收敛）。</li>
     * </ul>
     *
     * <p>{@code diffNow == null} 表示本次不可比（换了 tree 或生产源码变了）：条目原样保留、只走时间窗。
     * 旧实现把「不可比」当成「无差异」整批清空，与「永久常跑」（§3.6）自相矛盾，是 L-101 的口径缺口。
     *
     * @return {@code {active: List<String>, retired: List<String>, history: Map<String, Map<String,Object>>}}
     */
    static Map<String, Object> reviseUnstable(Map<String, Object> prevHistory,
                                              List<String> prevActive,
                                              Collection<String> diffNow,
                                              long nowMs, int cleanRuns, long maxAgeMs) {
        Map<String, Map<String, Object>> history = new TreeMap<>();
        if (prevHistory != null) {
            for (Map.Entry<String, Object> e : prevHistory.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> raw) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> f : raw.entrySet()) {
                        one.put(String.valueOf(f.getKey()), f.getValue());
                    }
                    history.put(e.getKey(), one);
                }
            }
        }
        Set<String> candidates = new TreeSet<>(prevActive == null ? List.of() : prevActive);
        if (diffNow != null) {
            candidates.addAll(diffNow);
        }
        Set<String> differed = diffNow == null ? Set.of() : new TreeSet<>(diffNow);

        Set<String> active = new TreeSet<>();
        List<String> retired = new ArrayList<>();
        for (String test : candidates) {
            Map<String, Object> entry = history.computeIfAbsent(test, k -> new LinkedHashMap<>());
            long firstDiffAt = num(entry.get("firstDiffAt"), nowMs);
            long lastDiffAt = num(entry.get("lastDiffAt"), nowMs);
            int streak = (int) num(entry.get("cleanStreak"), 0);
            boolean differs = diffNow != null && differed.contains(test);
            if (diffNow != null) {
                if (differs) {
                    if (!entry.containsKey("firstDiffAt")) {
                        firstDiffAt = nowMs;
                    }
                    lastDiffAt = nowMs;
                    streak = 0;
                } else {
                    streak += 1;
                    entry.put("lastCleanAt", nowMs);
                }
                entry.put("cleanStreak", streak);
            }
            entry.put("firstDiffAt", firstDiffAt);
            entry.put("lastDiffAt", lastDiffAt);
            boolean expired = nowMs - lastDiffAt > maxAgeMs;
            if (expired) {
                entry.put("retiredAt", nowMs);
                entry.put("retiredReason", "age-window");
                retired.add(test);
            } else if (diffNow != null && !differs && streak >= cleanRuns) {
                entry.put("retiredAt", nowMs);
                entry.put("retiredReason", "clean-streak");
                retired.add(test);
            } else {
                active.add(test);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", new ArrayList<>(active));
        out.put("retired", retired);
        out.put("history", new TreeMap<>(history));
        return out;
    }

    private static long num(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** 收尾：把最后一个类之后迟到的触达并入最后一个类（M4 收尾窗口），然后整表落盘。 */
    static void finalizeRun() {
        synchronized (TestmapRecorder.class) {
            if (!enabled() || finalized) {
                return;
            }
            if (lastTestClass != null) {
                record(lastTestClass, false);
            }
            finalized = true;
            try {
                writeAll();
            } catch (Throwable t) {
                System.err.println("[testmap] 写表失败：" + t);
                t.printStackTrace();
            }
            assertCoverageFloor();
        }
    }

    /**
     * B0 自测闸（K2）：归一后键集与源码 FQN 集重合 < 95% ⇒ 构建失败。
     *
     * <p>只对<b>全量录制</b>生效（{@code testmap.mode=full}）：选中运行只录一部分测试类，键覆盖天然不全。
     * 用 {@code Runtime.halt(1)} 而不是抛异常，是因为收尾跑在 JVM 关闭钩子里——抛异常只会被吞掉，
     * halt 才能让 surefire 的 fork 以非零码退出、把构建判红（设计稿 §3.8 的 fail-fast）。
     */
    static void assertCoverageFloor() {
        if (!fullMode()) {
            return;
        }
        Double ratio = keyOverlapConcrete;
        double min = Double.parseDouble(System.getProperty("testmap.b0.min", "0.95"));
        if (ratio != null && ratio < min) {
            System.err.printf("[testmap] B0 自测闸未过：可记录类键覆盖 %.2f%% < %.0f%%（全量口径 %.2f%%；"
                            + "见 target/testmap/map.json 的 meta.keyOverlapRatio / keyOverlapRatioConcrete / "
                            + "keyOverlapMissing），本次录制判失败。%n",
                    ratio * 100, min * 100, (keyOverlap == null ? -1 : keyOverlap * 100));
            Runtime.getRuntime().halt(1);
        }
        System.out.printf("[testmap] B0 键覆盖：全量口径 %.2f%%（含接口/注解，JaCoCo 结构性不可记录）、可记录类口径 %.2f%%%n",
                (keyOverlap == null ? -1 : keyOverlap * 100), ratio * 100);
    }

    static double coverageRatio() {
        Double ratio = keyOverlap;
        return ratio == null ? -1 : ratio;
    }

    // ------------------------------------------------------------ JaCoCo 读取

    private record ClassRec(long id, String vmName, boolean hit) {
    }

    private static List<ClassRec> snapshot(boolean reset) throws Exception {
        Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
        Object agent = rt.getMethod("getAgent").invoke(null);
        byte[] data = (byte[]) agent.getClass().getMethod("getExecutionData", boolean.class).invoke(agent, reset);
        return parse(data);
    }

    /** 解析 JaCoCo 执行数据流（与 org.jacoco.core.data.ExecutionDataWriter 同格式）。 */
    static List<ClassRec> parse(byte[] data) throws IOException {
        List<ClassRec> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int header = in.readUnsignedByte();
        if (header != 0x01) {
            throw new IOException("执行数据块头非法：0x" + Integer.toHexString(header));
        }
        in.readUnsignedShort(); // magic 0xC0C0
        in.readUnsignedShort(); // format version
        while (in.available() > 0) {
            int type = in.readUnsignedByte();
            if (type == 0x10) { // session info
                in.readUTF();
                in.readLong();
                in.readLong();
            } else if (type == 0x11) { // execution data
                long id = in.readLong();
                String name = in.readUTF();
                int probes = readVarInt(in);
                byte[] bits = new byte[(probes + 7) / 8];
                in.readFully(bits);
                boolean hit = false;
                for (byte b : bits) {
                    if (b != 0) {
                        hit = true;
                        break;
                    }
                }
                out.add(new ClassRec(id, name, hit));
            } else {
                throw new IOException("未知执行数据块：0x" + Integer.toHexString(type));
            }
        }
        return out;
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
    }

    // -------------------------------------------------------------- 归一与集合

    static String normalize(String vmName) {
        String n = vmName.replace('/', '.');
        int idx;
        while ((idx = n.lastIndexOf('$')) >= 0) {
            n = n.substring(0, idx);
        }
        return n;
    }

    /** {@code @Nested} 测试类 {@code Outer$Inner} 归到文件级最外类：倒排表以「surefire 能跑的单位」为键。 */
    static String normalizeTestKey(String dottedFqn) {
        int idx = dottedFqn.indexOf('$');
        return idx < 0 ? dottedFqn : dottedFqn.substring(0, idx);
    }

    static boolean isSynthetic(String vmName) {
        for (String marker : SYNTHETIC_MARKERS) {
            if (vmName.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static synchronized Map<String, Path> prodSources() {
        if (prodSources != null) {
            return prodSources;
        }
        Path root = Paths.get("src", "main", "java");
        Map<String, Path> found = new TreeMap<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path p : stream.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".java") || name.equals("package-info.java") || name.equals("module-info.java")) {
                        continue;
                    }
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    found.put(rel.substring(0, rel.length() - 5).replace('/', '.'), p);
                }
            } catch (IOException e) {
                System.err.println("[testmap] 扫描 src/main/java 失败：" + e);
            }
        }
        prodSources = Collections.unmodifiableMap(found);
        return prodSources;
    }

    /**
     * 可记录类 = 顶层声明不是 {@code interface} / {@code @interface} 的生产类。
     *
     * <p>JaCoCo 只给有可执行探测点的类型产生 execution data：纯接口与注解类型没有字节码体，
     * 结构上永远不会出现在执行数据里。B0 的原始口径（全部 {@code *.java}）因此被这类文件
     * 系统性压低（本仓 24 个接口 + 4 个注解 / 252 个生产类 ⇒ 天花板约 88.9%），
     * 故闸门按「可记录类口径」判，同时把两个比例都写进 meta 供审计。
     */
    static synchronized Set<String> concreteProdClasses(Map<String, Path> prod) {
        if (concreteProd != null) {
            return concreteProd;
        }
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Path> e : prod.entrySet()) {
            String kind = topLevelKind(e.getValue());
            if (!"interface".equals(kind) && !"@interface".equals(kind)) {
                out.add(e.getKey());
            }
        }
        concreteProd = Collections.unmodifiableSet(out);
        return concreteProd;
    }

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*"
                    + "(class|interface|@interface|enum|record)\\b");

    private static String topLevelKind(Path source) {        try {
            Matcher m = TOP_LEVEL_TYPE.matcher(Files.readString(source, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "class";
        } catch (IOException e) {
            return "class";
        }
    }

    /** {@code @SpringBootTest} 测试类（固定冒烟集里的上下文冒烟，§3.6）：录制期扫一次存进 meta，省选择器的 git grep。 */
    private static List<String> contextTests() {
        Path root = Paths.get("src", "test", "java");
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path p : stream.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    if (!p.toString().endsWith(".java")) {
                        continue;
                    }
                    if (!p.getFileName().toString().endsWith("Test.java")) {
                        continue;
                    }
                    try {
                        if (Files.readString(p, StandardCharsets.UTF_8).contains("@SpringBootTest")) {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            found.add(rel.substring(0, rel.length() - 5).replace('/', '.'));
                        }
                    } catch (IOException ignored) {
                        // 单个文件读失败不影响整体
                    }
                }
            } catch (IOException e) {
                System.err.println("[testmap] 扫描 src/test/java 失败：" + e);
            }
        }
        Collections.sort(found);
        return found.size() > 2 ? new ArrayList<>(found.subList(0, 2)) : found;
    }

    /** 生产类的 blob sha：取 HEAD 树里的 blob（git ls-tree），与选择器的 {@code git rev-parse HEAD:<path>} 同源。 */
    static synchronized Map<String, String> prodBlobs() {
        if (prodBlobs != null) {
            return prodBlobs;
        }
        Map<String, String> blobs = new TreeMap<>();
        String out = git("ls-tree", "-r", "-z", "HEAD", "--", "src/main/java");
        if (out != null) {
            for (String rec : out.split("\u0000")) {
                int tab = rec.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String[] head = rec.substring(0, tab).split(" ");
                String path = rec.substring(tab + 1);
                int marker = path.indexOf("src/main/java/");
                if (head.length < 3 || marker < 0) {
                    continue;
                }
                String rel = path.substring(marker + "src/main/java/".length());
                if (!rel.endsWith(".java")) {
                    continue;
                }
                blobs.put(rel.substring(0, rel.length() - 5).replace('/', '.'), head[2]);
            }
        }
        prodBlobs = Collections.unmodifiableMap(blobs);
        return prodBlobs;
    }

    /** HEAD 变了但生产源码没变（只想改测试/脚本）时，仍可与旧表做同源比对。 */
    private static boolean prodSourcesUnchangedSince(String oldHead) {
        if (oldHead == null || "unknown".equals(oldHead)) {
            return false;
        }
        String changed = git("diff", "--name-only", oldHead + "..HEAD", "--", "src/main/java");
        return changed != null && changed.isEmpty();
    }

    private static String headSha() {
        if (headSha == null) {
            headSha = git("rev-parse", "HEAD");
            if (headSha == null) {
                headSha = "unknown";
            }
        }
        return headSha;
    }

    private static String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            System.err.println("[testmap] git " + String.join(" ", args) + " 失败：" + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ 落盘

    private static void ensureHook() {
        if (hookInstalled) {
            return;
        }
        synchronized (TestmapRecorder.class) {
            if (hookInstalled) {
                return;
            }
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    finalizeRun();
                } catch (Throwable ignored) {
                    // 关闭钩子不再抛
                }
            }, "testmap-shutdown"));
        }
    }

    private static void writeAll() throws IOException {
        Files.createDirectories(OUT_DIR.resolve("raw"));
        Map<String, Path> prod = prodSources();
        Map<String, String> blobs = prodBlobs();

        Map<String, List<String>> current = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : DEPS.entrySet()) {
            current.put(e.getKey(), new ArrayList<>(new TreeSet<>(e.getValue())));
        }

        Map<String, Object> previous = readJson(OUT_DIR.resolve("map.json"));
        Map<String, List<String>> oldTests = testsOf(previous);
        String oldHead = previous == null ? null : String.valueOf(metaOf(previous).get("headSha"));
        String oldMode = previous == null ? null : String.valueOf(metaOf(previous).get("mode"));
        boolean oldDeterministic = previous != null
                && Boolean.TRUE.equals(metaOf(previous).get("deterministic"));

        Map<String, List<String>> merged = new TreeMap<>();
        if (!fullMode() && oldTests != null) {
            merged.putAll(oldTests);
        }
        merged.putAll(current);

        // 确定性比对（F12/B7）：同 tree、同为全量录制，逐测试类比依赖集
        int diff = 0;
        List<String> unstable = new ArrayList<>();
        boolean compared = false;
        if (fullMode() && oldTests != null && "full".equals(oldMode)
                && (Objects.equals(oldHead, headSha()) || prodSourcesUnchangedSince(oldHead))) {
            compared = true;
            TreeSet<String> all = new TreeSet<>(oldTests.keySet());
            all.addAll(merged.keySet());
            for (String test : all) {
                Set<String> a = new TreeSet<>(oldTests.getOrDefault(test, List.of()));
                Set<String> b = new TreeSet<>(merged.getOrDefault(test, List.of()));
                if (!a.equals(b)) {
                    diff++;
                    unstable.add(test);
                }
            }
            // 并集修复：差异来自后台线程/异步回调的迟到触达（M4），两侧条目都可能是真的。
            // 取并集是保守方向（只会多选测试），并把差异类钉进永久冒烟集（§3.6）；
            // B7 的原始数字仍原样记在 deterministicDiff / unstableTests 里，不粉饰。
            for (String test : unstable) {
                Set<String> union = new TreeSet<>(oldTests.getOrDefault(test, List.of()));
                union.addAll(merged.getOrDefault(test, List.of()));
                merged.put(test, new ArrayList<>(union));
            }
        }
        boolean repaired = compared && diff > 0;

        // L-101：不稳定集合不是「一次录制即永久」——按「连续 N 次干净 / 时间窗」重估。
        Map<String, Object> prevMeta = metaOf(previous);
        @SuppressWarnings("unchecked")
        Map<String, Object> revised = reviseUnstable(
                (Map<String, Object>) prevMeta.get("unstableHistory"),
                stringList(prevMeta.get("unstableTests")),
                compared ? unstable : null,
                System.currentTimeMillis(),
                unstableRetireCleanRuns(),
                unstableMaxAgeMs());
        List<String> activeUnstable = stringList(revised.get("active"));
        List<String> retiredUnstable = stringList(revised.get("retired"));

        Map<String, Object> index = new TreeMap<>();
        int pairs = 0;
        for (Map.Entry<String, List<String>> e : merged.entrySet()) {
            for (String dep : e.getValue()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) index.computeIfAbsent(dep, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tests", new ArrayList<String>());
                    m.put("blobSha", blobs.get(k));
                    return m;
                });
                @SuppressWarnings("unchecked")
                List<String> tests = (List<String>) entry.get("tests");
                tests.add(e.getKey());
                pairs++;
            }
        }

        Set<String> union = new TreeSet<>();
        merged.values().forEach(union::addAll);
        List<String> missing = new ArrayList<>();
        for (String fqn : prod.keySet()) {
            if (!union.contains(fqn)) {
                missing.add(fqn);
            }
        }
        double ratio = prod.isEmpty() ? 1.0 : (prod.size() - missing.size()) / (double) prod.size();
        keyOverlap = ratio;
        Set<String> concrete = concreteProdClasses(prod);
        long missingConcrete = missing.stream().filter(concrete::contains).count();
        double concreteRatio = concrete.isEmpty() ? 1.0 : (concrete.size() - missingConcrete) / (double) concrete.size();
        keyOverlapConcrete = concreteRatio;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("builtAt", OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")));
        meta.put("builtAtEpochMs", System.currentTimeMillis());
        meta.put("headSha", headSha());
        meta.put("jacoco", JACOCO_VERSION);
        meta.put("mode", fullMode() ? "full" : "incremental");
        meta.put("deterministic", compared ? diff == 0 : (previous == null || oldDeterministic));
        meta.put("deterministicChecked", compared);
        meta.put("deterministicCompared", compared);
        meta.put("deterministicDiff", compared ? diff : -1);
        meta.put("unionRepaired", repaired);
        meta.put("unstableTests", activeUnstable);
        meta.put("unstableRetired", retiredUnstable);
        meta.put("unstableHistory", revised.get("history"));
        meta.put("unstablePolicy", Map.of(
                "cleanRuns", unstableRetireCleanRuns(),
                "maxAgeDays", TimeUnit.MILLISECONDS.toDays(unstableMaxAgeMs()),
                "source", "L-101"));
        meta.put("deterministicDiffClasses", compared ? unstable : null);
        Map<String, Object> durations = new TreeMap<>();
        // 增量录制只跑了子集：旧计时先并入，再用本轮实测覆盖（否则子集录制会把其余类的计时清空）
        if (!fullMode() && prevMeta.get("testClassDurationMs") instanceof Map<?, ?> previousDur) {
            for (Map.Entry<?, ?> e : previousDur.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    durations.put(String.valueOf(e.getKey()), n.longValue());
                }
            }
        }
        for (Map.Entry<String, Long> e : DURATIONS.entrySet()) {
            if (merged.containsKey(e.getKey())) {
                durations.put(e.getKey(), e.getValue());
            }
        }
        durations.keySet().retainAll(merged.keySet());
        meta.put("testClassDurationMs", durations);
        // 墙钟只在全量录制时更新；增量录制沿用上一轮（否则「全量墙钟」会被子集耗时污染）
        Object wall = fullMode() && startedAtMs != 0 ? System.currentTimeMillis() - startedAtMs : null;
        if (wall == null && prevMeta.get("recordedWallMs") instanceof Number n) {
            wall = n;
        }
        meta.put("recordedWallMs", wall);
        meta.put("testClassCount", merged.size());
        meta.put("prodClassCount", prod.size());
        meta.put("depPairCount", pairs);
        meta.put("keyOverlapRatio", Math.round(ratio * 10000) / 10000.0);
        meta.put("keyOverlapRatioConcrete", Math.round(concreteRatio * 10000) / 10000.0);
        meta.put("concreteProdClassCount", concrete.size());
        meta.put("keyOverlapMissing", missing);
        meta.put("testClassesWithoutDeps",
                merged.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).collect(Collectors.toList()));
        meta.put("failedTestClasses", new ArrayList<>(new TreeSet<>(FAILED)));
        meta.put("contextTests", contextTests());
        meta.put("gitAvailable", !blobs.isEmpty());
        meta.put("agentFailure", agentFailure == null ? null : String.valueOf(agentFailure));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("tests", merged);
        root.put("index", index);
        JSON.writeValue(OUT_DIR.resolve("map.json").toFile(), root);

        // 原始快照（含 hit 标记，排障用；不参与选择）
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("meta", meta);
        Map<String, Object> rawTests = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : DEPS.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("deps", new ArrayList<>(new TreeSet<>(e.getValue())));
            one.put("hits", new ArrayList<>(new TreeSet<>(HITS.getOrDefault(e.getKey(), Set.of()))));
            rawTests.put(e.getKey(), one);
        }
        raw.put("tests", rawTests);
        JSON.writeValue(OUT_DIR.resolve("raw").resolve(headSha() + "-" + ProcessHandle.current().pid() + ".json").toFile(), raw);

        System.out.printf("[testmap] 录制完成：测试类 %d、生产类 %d、依赖对 %d、键覆盖 %.2f%%%s%n",
                merged.size(), prod.size(), pairs, ratio * 100,
                compared ? ("、确定性 diff=" + diff) : "");
        if (missing.size() > 0) {
            System.out.println("[testmap] 未被任何测试触达的生产类（前 10）：" + missing.stream().limit(10).toList());
        }
        if (compared && diff != 0) {
            System.out.println("[testmap] 两次录制不一致的测试类（前 10）：" + unstable.stream().limit(10).toList());
        }
        if (!activeUnstable.isEmpty() || !retiredUnstable.isEmpty()) {
            System.out.printf("[testmap] 不稳定集合（L-101）：在用 %d 类、本轮移出 %d 类%s（策略：连续 %d 次干净或超 %d 天）%n",
                    activeUnstable.size(), retiredUnstable.size(),
                    retiredUnstable.isEmpty() ? "" : "（" + retiredUnstable + "）",
                    unstableRetireCleanRuns(), TimeUnit.MILLISECONDS.toDays(unstableMaxAgeMs()));
        }
        if (!blobs.isEmpty() && blobs.size() < prod.size()) {
            System.out.println("[testmap] 提示：有生产类在 HEAD 树里没有 blob（未入库），blobSha 记 null");
        }
    }

    private static Map<String, Object> readJson(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = JSON.readValue(path.toFile(), Map.class);
            return root;
        } catch (Exception e) {
            System.err.println("[testmap] 旧表不可读，按首跑处理：" + e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(Map<String, Object> root) {
        Object meta = root == null ? null : root.get("meta");
        return meta instanceof Map ? (Map<String, Object>) meta : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> testsOf(Map<String, Object> root) {
        Object tests = root == null ? null : root.get("tests");
        if (!(tests instanceof Map)) {
            return null;
        }
        Map<String, List<String>> out = new TreeMap<>();
        ((Map<String, Object>) tests).forEach((k, v) -> {
            if (v instanceof List<?> list) {
                out.put(k, list.stream().map(String::valueOf).collect(Collectors.toList()));
            }
        });
        return out;
    }
}
