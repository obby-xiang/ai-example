package com.example.configmgr.ai.web;

import com.example.configmgr.ai.session.AiSessionService;
import com.example.configmgr.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>C-1：多构造器的 {@code @RestController} 必须能被 Spring 实例化</b>。
 *
 * <h2>缺陷形态</h2>
 * 单测都直接 {@code new AiSessionController(generator, sessions)}，{@code mvn compile} 也不碰装配，
 * 所以"构造器配错"在编译期与单测里都看不见。一旦类上有<b>两个</b>构造器且都没 {@code @Autowired}：
 * Spring 的 {@code determineCandidateConstructors} 找不到唯一的候选（也没有无参构造器）⇒
 * 退回 {@code instantiateBean} ⇒ {@code getDeclaredConstructor()} 抛 {@code NoSuchMethodException} ⇒
 * 整个应用上下文刷新失败（不是只有 {@code /api/ai/session} 不可用，而是<b>启动即失败</b>）。
 *
 * <p>本用例用最小 Spring 上下文真实装配一次该控制器：只要生产构造器没有被 Spring 选中，
 * 刷新就直接抛异常。仓库既有约定（{@code SessionGate}、{@code ConfirmGate}、{@code GenerativeFormGuard}）
 * 同样是"多构造器时给 Spring 用的那个加 {@code @Autowired}"。
 */
class AiSessionControllerWiringTest {

	@Test
	void springInstantiatesTheControllerWhenTheClassHasMoreThanOneConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(IdGenerator.class, () -> mock(IdGenerator.class));
			context.registerBean(AiSessionService.class, () -> mock(AiSessionService.class));
			context.register(AiSessionController.class);

			context.refresh();

			assertThat(context.getBean(AiSessionController.class)).as("容器必须能实例化发号控制器").isNotNull();
		}
	}

}
