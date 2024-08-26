/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2020 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.opengl.shader;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static org.lwjgl.opengl.GL41C.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
public class Shader
{
	public static final boolean DUMP_SHADERS = Props.has("rlhd.dump-shaders");

	@VisibleForTesting
	final List<Unit> units = new ArrayList<>();

	@RequiredArgsConstructor
	@VisibleForTesting
	static class Unit
	{
		@Getter
		public final int type;

		@Getter
		public final String filename;
	}

	public Shader add(int type, String name)
	{
		units.add(new Unit(type, name));
		return this;
	}

	public int compile(Template template, boolean useSpriv) throws ShaderException, IOException
	{
		int program = glCreateProgram();
		int[] shaders = new int[units.size()];
		int i = 0;
		boolean ok = false;

		ResourcePath dumpPath = null;
		if (DUMP_SHADERS)
			dumpPath = path("shader-dumps").mkdirs();

		try
		{
			while (i < shaders.length) {
				Unit unit = units.get(i);
				int shader = glCreateShader(unit.type);
				if (shader == 0) {
					throw new ShaderException("Unable to create shader of type " + unit.type);
				}

				String source = template.load(unit.filename);
				if (DUMP_SHADERS)
					dumpPath.resolve(unit.filename).writeString(source);

				if(useSpriv) {
					int spriv_shader_type =
						unit.type == GL_VERTEX_SHADER ? shaderc_glsl_vertex_shader :
						unit.type == GL_FRAGMENT_SHADER ? shaderc_glsl_fragment_shader :
						unit.type == GL_GEOMETRY_SHADER ? shaderc_glsl_geometry_shader : shaderc_glsl_compute_shader;

					long spiv_compiler = shaderc_compiler_initialize();
					long spiv_options = shaderc_compile_options_initialize();

					shaderc_compile_options_set_source_language(spiv_options, shaderc_source_language_glsl);
					shaderc_compile_options_set_target_env(spiv_options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
					shaderc_compile_options_set_optimization_level(spiv_options, shaderc_optimization_level_performance);
					shaderc_compile_options_set_generate_debug_info(spiv_options);

					ByteBuffer spiv_entry_point = MemoryUtil.memUTF8("main", true);
					ByteBuffer spiv_source_file = MemoryUtil.memUTF8("", true);
					ByteBuffer spiv_source_data = MemoryUtil.memUTF8(source, false);

					long compileResult = shaderc_compile_into_spv(
						spiv_compiler,
						spiv_source_data,
						spriv_shader_type,
						spiv_source_file,
						spiv_entry_point,
						spiv_options
					);

					MemoryUtil.memFree(spiv_entry_point);
					MemoryUtil.memFree(spiv_source_file);
					MemoryUtil.memFree(spiv_source_data);

					if (shaderc_result_get_compilation_status(compileResult) != shaderc_compilation_status_success) {
						String err = shaderc_result_get_error_message(compileResult);
						glDeleteShader(shader);
						shaderc_result_release(compileResult);
						throw ShaderException.compileError(err, template, unit);
					}

					ByteBuffer shaderSpirvBinary = shaderc_result_get_bytes(compileResult);

					GL46.glShaderBinary(new int[] {shader}, GL46.GL_SHADER_BINARY_FORMAT_SPIR_V, shaderSpirvBinary );
					GL46.glSpecializeShader(shader, "main", (int[])null, null);

					shaderc_compiler_release(spiv_compiler);
					shaderc_compile_options_release(spiv_options);
					shaderc_result_release(compileResult);
				} else {
					glShaderSource(shader, source);
				}
				
				glCompileShader(shader);

				if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
				{
					String err = glGetShaderInfoLog(shader);
					glDeleteShader(shader);
					throw ShaderException.compileError(err, template, unit);
				}

				glAttachShader(program, shader);
				shaders[i++] = shader;
			}

			glLinkProgram(program);

			if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
			{
				String err = glGetProgramInfoLog(program);
				throw ShaderException.compileError(err, template, units.toArray(new Unit[0]));
			}

			ok = true;

			if (DUMP_SHADERS) {
				int[] numFormats = { 0 };
				glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, numFormats);
				if (numFormats[0] < 1) {
					log.error("OpenGL driver does not support any binary formats");
				} else {
					int[] size = { 0 };
					glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, size);

					int[] format = { 0 };
					ByteBuffer binary = BufferUtils.createByteBuffer(size[0]);
					glGetProgramBinary(program, size, format, binary);

					String shaderName =
						units.stream()
							.map(Unit::getFilename)
							.collect(Collectors.joining(" + ")) + ".bin";
					dumpPath.resolve(shaderName).writeByteBuffer(binary);
				}
			}
		}
		finally
		{
			while (i > 0)
			{
				int shader = shaders[--i];
				glDetachShader(program, shader);
				glDeleteShader(shader);
			}

			if (!ok)
			{
				glDeleteProgram(program);
			}
		}

		return program;
	}
}
