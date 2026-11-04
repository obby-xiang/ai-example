package com.example.configmgr.perf.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** 输出只写 PERF_OUTPUT_DIR；不写密码、不写环境原值。 */
public final class OutputWriter {

	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private final Path directory;

	public OutputWriter(Path directory) {
		this.directory = directory;
		try {
			Files.createDirectories(directory);
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法创建输出目录 " + directory, ex);
		}
	}

	public Path directory() {
		return this.directory;
	}

	public Path json(String name, Object value) {
		Path path = this.directory.resolve(name);
		try {
			MAPPER.writeValue(path.toFile(), value);
			return path;
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法写入 " + path, ex);
		}
	}

	public void csv(String name, List<String> header, List<List<String>> rows) {
		Path path = this.directory.resolve(name);
		StringBuilder sb = new StringBuilder();
		sb.append(String.join(",", header)).append('\n');
		for (List<String> row : rows) {
			for (int i = 0; i < row.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(escape(row.get(i)));
			}
			sb.append('\n');
		}
		try {
			Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法写入 " + path, ex);
		}
	}

	public void text(String name, String content) {
		try {
			Files.writeString(this.directory.resolve(name), content, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法写入 " + name, ex);
		}
	}

	public String read(String name) {
		try {
			Path path = this.directory.resolve(name);
			return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	private static String escape(String value) {
		if (value == null) {
			return "";
		}
		if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
			return '"' + value.replace("\"", "\"\"") + '"';
		}
		return value;
	}

}
