package net.coderbot.iris.pipeline.newshader;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.blending.AlphaTest;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.SodiumTerrainPipeline;
import net.coderbot.iris.pipeline.newshader.flw.FlwProgramType;
import net.coderbot.iris.pipeline.patcher.AttributeShaderTransformer;
import net.coderbot.iris.pipeline.patcher.CompositeDepthTransformer;
import net.coderbot.iris.shaderpack.transform.BuiltinUniformReplacementTransformer;
import net.coderbot.iris.shaderpack.transform.StringTransformations;
import net.coderbot.iris.shaderpack.transform.Transformations;

public class TriforcePatcher {
	public static void patchCommon(StringTransformations transformations, ShaderType type) {
		// TODO: Only do the NewLines patches if the source code isn't from gbuffers_lines

		if (transformations.contains("moj_import")) {
			throw new IllegalStateException("Iris shader programs may not use moj_import directives.");
		}

		if (transformations.contains("iris_")) {
			throw new IllegalStateException("Detected a potential reference to unstable and internal Iris shader interfaces (iris_). This isn't currently supported.");
		}

		fixVersion(transformations);

		// This must be defined and valid in all shader passes, including composite passes.
		//
		// A shader that relies on this behavior is SEUS v11 - it reads gl_Fog.color and breaks if it is not properly
		// defined.
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform float iris_FogDensity;\n" +
				"uniform float iris_FogStart;\n" +
				"uniform float iris_FogEnd;\n" +
				"uniform vec4 iris_FogColor;\n" +
				"\n" +
				"struct iris_FogParameters {\n" +
				"    vec4 color;\n" +
				"    float density;\n" +
				"    float start;\n" +
				"    float end;\n" +
				"    float scale;\n" +
				"};\n" +
				"\n" +
				"iris_FogParameters iris_Fog = iris_FogParameters(iris_FogColor, iris_FogDensity, iris_FogStart, iris_FogEnd, 1.0 / (iris_FogEnd - iris_FogStart));\n" +
				"\n" +
				"#define gl_Fog iris_Fog");

		// TODO: What if the shader does gl_PerVertex.gl_FogFragCoord ?
		transformations.define("gl_FogFragCoord", "iris_FogFragCoord");

		// TODO: This doesn't handle geometry shaders... How do we do that?
		if (type == ShaderType.VERTEX) {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "out float iris_FogFragCoord;");
		} else if (type == ShaderType.FRAGMENT) {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in float iris_FogFragCoord;");
		}

		if (type == ShaderType.VERTEX) {
			// TODO: This is incorrect and is just the bare minimum needed for SEUS v11 & Renewed to compile
			// It works because they don't actually use gl_FrontColor even though they write to it.
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 iris_FrontColor;");
			transformations.define("gl_FrontColor", "iris_FrontColor");
		}


		if (type == ShaderType.FRAGMENT) {
			if (transformations.contains("gl_FragColor")) {
				// TODO: Find a way to properly support gl_FragColor
				Iris.logger.warn("[Triforce Patcher] gl_FragColor is not supported yet, please use gl_FragData! Assuming that the shaderpack author intended to use gl_FragData[0]...");
				transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_FragColor iris_FragData[0]");
			}

			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_FragData iris_FragData");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "layout (location = 0) out vec4 iris_FragData[8];");
		}

		if (type == ShaderType.VERTEX) {
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define attribute in");
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define varying out");
		} else if (type == ShaderType.FRAGMENT) {
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define varying in");
		}

		// TODO: Add similar functions for all legacy texture sampling functions
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture2D(sampler2D sampler, vec2 coord) { return texture(sampler, coord); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture3D(sampler3D sampler, vec3 coord) { return texture(sampler, coord); }");

		if (type == ShaderType.FRAGMENT) {
			// GLSL 1.50 Specification, Section 8.7:
			//    In all functions below, the bias parameter is optional for fragment shaders.
			//    The bias parameter is not accepted in a vertex or geometry shader.
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture2D(sampler2D sampler, vec2 coord, float bias) { return texture(sampler, coord, bias); }");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture3D(sampler3D sampler, vec3 coord, float bias) { return texture(sampler, coord, bias); }");
		}

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture2DLod(sampler2D sampler, vec2 coord, float lod) { return textureLod(sampler, coord, lod); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture3DLod(sampler3D sampler, vec3 coord, float lod) { return textureLod(sampler, coord, lod); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 shadow2D(sampler2DShadow sampler, vec3 coord) { return vec4(texture(sampler, coord)); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 shadow2DLod(sampler2DShadow sampler, vec3 coord, float lod) { return vec4(textureLod(sampler, coord, lod)); }");

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "ivec4 texture2D(isampler2D sampler, ivec2 coord) { return texture(sampler, coord); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uvec4 texture2D(usampler2D sampler, uvec2 coord) { return texture(sampler, coord); }");

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture2DGrad(sampler2D sampler, vec2 coord, vec2 dPdx, vec2 dPdy) { return textureGrad(sampler, coord, dPdx, dPdy); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture2DGradARB(sampler2D sampler, vec2 coord, vec2 dPdx, vec2 dPdy) { return textureGrad(sampler, coord, dPdx, dPdy); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texture3DGrad(sampler3D sampler, vec3 coord, vec3 dPdx, vec3 dPdy) { return textureGrad(sampler, coord, dPdx, dPdy); }");

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texelFetch2D(sampler2D sampler, ivec2 coord, int lod) { return texelFetch(sampler, coord, lod); }");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 texelFetch3D(sampler3D sampler, ivec3 coord, int lod) { return texelFetch(sampler, coord, lod); }");

		//System.out.println(transformations.toString());
	}

	public static String patchVanilla(String source, ShaderType type, AlphaTest alpha, boolean hasChunkOffset, ShaderAttributeInputs inputs, boolean hasGeometry) {
		StringTransformations transformations = new StringTransformations(source);

		patchCommon(transformations, type);

		if (inputs.hasOverlay()) {
			AttributeShaderTransformer.patchOverlayColor(transformations, type, hasGeometry);
		}

		addAlphaTest(transformations, type, alpha);

		transformations.define("gl_ProjectionMatrix", "iris_ProjMat");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_ProjMat;");

		if (type == ShaderType.VERTEX) {
			if (inputs.hasTex()) {
				transformations.define("gl_MultiTexCoord0", "vec4(iris_UV0, 0.0, 1.0)");
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec2 iris_UV0;");
			} else {
				transformations.define("gl_MultiTexCoord0", "vec4(0.5, 0.5, 0.0, 1.0)");
			}

			if (inputs.hasLight()) {
				transformations.define("gl_MultiTexCoord1", "vec4(iris_UV2, 0.0, 1.0)");
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in ivec2 iris_UV2;");
			} else {
				transformations.define("gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
			}

			// Alias of gl_MultiTexCoord1 on 1.15+ for OptiFine
			// See https://github.com/IrisShaders/Iris/issues/1149
			transformations.define("gl_MultiTexCoord2", "gl_MultiTexCoord1");

			if (transformations.contains("gl_MultiTexCoord3") && !transformations.contains("mc_midTexCoord")) {
				// gl_MultiTexCoord3 is a super legacy alias of mc_midTexCoord. We don't do this replacement if
				// we think mc_midTexCoord could be defined just we can't handle an existing declaration robustly.
				//
				// But basically the proper way to do this is to define mc_midTexCoord only if it's not defined, and if
				// it is defined, figure out its type, then replace all occurrences of gl_MultiTexCoord3 with the correct
				// conversion from mc_midTexCoord's declared type to vec4.
				transformations.replaceExact("gl_MultiTexCoord3", "mc_midTexCoord");
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "attribute vec4 mc_midTexCoord;");
			}

			// gl_MultiTexCoord0 and gl_MultiTexCoord1 are the only valid inputs (with gl_MultiTexCoord2 and gl_MultiTexCoord3 as aliases), other texture coordinates are not valid inputs.
			for (int i = 4; i < 8; i++) {
				transformations.define("gl_MultiTexCoord" + i, " vec4(0.0, 0.0, 0.0, 1.0)");
			}
		}

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform vec4 iris_ColorModulator;");

		if (inputs.hasColor()) {
			// TODO: Handle the fragment / geometry shader here
			if (alpha == AlphaTests.VERTEX_ALPHA) {
				// iris_ColorModulator.a should be applied regardless of the alpha test state.
				transformations.define("gl_Color", "vec4((iris_Color * iris_ColorModulator).rgb, iris_ColorModulator.a)");
			} else {
				transformations.define("gl_Color", "(iris_Color * iris_ColorModulator)");
			}

			if (type == ShaderType.VERTEX) {
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec4 iris_Color;");
			}
		} else {
			// iris_ColorModulator should be applied regardless of the alpha test state.
			transformations.define("gl_Color", "iris_ColorModulator");
		}

		if (type == ShaderType.VERTEX) {
			if (inputs.hasNormal()) {
				if (!inputs.isNewLines()) {
					transformations.define("gl_Normal", "iris_Normal");
				} else {
					transformations.define("gl_Normal", "vec3(0.0, 0.0, 1.0)");
				}

				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec3 iris_Normal;");
			} else {
				transformations.define("gl_Normal", "vec3(0.0, 0.0, 1.0)");
			}
		}

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_LightmapTextureMatrix;");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_TextureMat;");

		// TODO: More solid way to handle texture matrices
		transformations.replaceExact("gl_TextureMatrix[0]", "iris_TextureMat");
		transformations.replaceExact("gl_TextureMatrix[1]", "iris_LightmapTextureMatrix");

		// TODO: Should probably add the normal matrix as a proper uniform that's computed on the CPU-side of things
		transformations.define("gl_NormalMatrix", "mat3(transpose(inverse(gl_ModelViewMatrix)))");

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_ModelViewMat;");

		if (hasChunkOffset) {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform vec3 iris_ChunkOffset;");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "mat4 _iris_internal_translate(vec3 offset) {\n" +
					"    // NB: Column-major order\n" +
					"    return mat4(1.0, 0.0, 0.0, 0.0,\n" +
					"                0.0, 1.0, 0.0, 0.0,\n" +
					"                0.0, 0.0, 1.0, 0.0,\n" +
					"                offset.x, offset.y, offset.z, 1.0);\n" +
					"}");
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewMatrix (iris_ModelViewMat * _iris_internal_translate(iris_ChunkOffset))");
		} else if (inputs.isNewLines()) {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE,
					"const float iris_VIEW_SHRINK = 1.0 - (1.0 / 256.0);\n" +
							"const mat4 iris_VIEW_SCALE = mat4(\n" +
							"    iris_VIEW_SHRINK, 0.0, 0.0, 0.0,\n" +
							"    0.0, iris_VIEW_SHRINK, 0.0, 0.0,\n" +
							"    0.0, 0.0, iris_VIEW_SHRINK, 0.0,\n" +
							"    0.0, 0.0, 0.0, 1.0\n" +
							");");
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewMatrix (iris_VIEW_SCALE * iris_ModelViewMat)");
		} else {
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewMatrix iris_ModelViewMat");
		}

		// TODO: All of the transformed variants of the input matrices, preferably computed on the CPU side...
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewProjectionMatrix (gl_ProjectionMatrix * gl_ModelViewMatrix)");

		if (type == ShaderType.VERTEX) {
			if (inputs.isNewLines()) {
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec3 iris_vertex_offset = vec3(0.0);");
				transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_Vertex vec4(iris_Position + iris_vertex_offset, 1.0)");

				if (transformations.contains("irisMain")) {
					throw new IllegalStateException("Shader already contains \"irisMain\"???");
				}

				// Create our own main function to wrap the existing main function, so that we can do our line shenanagains.
				transformations.replaceExact("main", "irisMain");

				transformations.injectLine(Transformations.InjectionPoint.END,
						"uniform vec2 iris_ScreenSize;\n" +
								"uniform float iris_LineWidth;\n" +
								"\n" +
								"// Widen the line into a rectangle of appropriate width\n" +
								"// Isolated from Minecraft's rendertype_lines.vsh\n" +
								"// Both arguments are positions in NDC space (the same space as gl_Position)\n" +
								"void iris_widen_lines(vec4 linePosStart, vec4 linePosEnd) {\n" +
								"    vec3 ndc1 = linePosStart.xyz / linePosStart.w;\n" +
								"    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;\n" +
								"\n" +
								"    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * iris_ScreenSize);\n" +
								"    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * iris_LineWidth / iris_ScreenSize;\n" +
								"\n" +
								"    if (lineOffset.x < 0.0) {\n" +
								"        lineOffset *= -1.0;\n" +
								"    }\n" +
								"\n" +
								"    if (gl_VertexID % 2 == 0) {\n" +
								"        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);\n" +
								"    } else {\n" +
								"        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);\n" +
								"    }\n" +
								"}\n" +
								"\n" +
								"void main() {\n" +
								"    iris_vertex_offset = iris_Normal;\n" +
								"    irisMain();\n" +
								"    vec4 linePosEnd = gl_Position;\n" +
								"    gl_Position = vec4(0.0);\n" +
								"\n" +
								"    iris_vertex_offset = vec3(0.0);\n" +
								"    irisMain();\n" +
								"    vec4 linePosStart = gl_Position;\n" +
								"\n" +
								"    iris_widen_lines(linePosStart, linePosEnd);\n" +
								"}");
			} else {
				transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_Vertex vec4(iris_Position, 1.0)");
			}

			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec3 iris_Position;");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");
		}

		applyIntelHd4000Workaround(transformations);

		return transformations.toString();
	}

	private static void addFlwAlphaTest(StringTransformations transformations, ShaderType type, float alphaDiscard) {
		if (alphaDiscard <= 0) {
			return;
		}

		if (type == ShaderType.FRAGMENT) {
			if (transformations.contains("irisMain")) {
				throw new IllegalStateException("Shader already contains \"irisMain\"???");
			}

			// Create our own main function to wrap the existing main function, so that we can run the alpha test at the
			// end.
			transformations.replaceExact("main", "irisMain");
			transformations.injectLine(Transformations.InjectionPoint.END, "void main() {\n" +
				"    irisMain();\n" +
				"\n" +
				"    if (gl_FragData[0].a < " + alphaDiscard + ") {\n" +
				"        discard;\n" +
				"    }\n" +
				"}");
		}
	}

	private static final String FLW_QUAT_UTIL =
		"""
#define PIOVER2 1.5707963268

vec4 quat(vec3 axis, float angle) {
    float halfAngle = angle * PIOVER2 / 180.0;
    vec2 cs = sin(vec2(PIOVER2 - halfAngle, halfAngle)); // compute sin and cos in one instruction
    return vec4(axis.xyz * cs.y,  cs.x);
}

vec4 quatMult(vec4 q1, vec4 q2) {
    // disgustingly vectorized quaternion multiplication
    vec4 a = q1.w * q2.xyzw;
    vec4 b = q1.x * q2.wzxy * vec4(1., -1., 1., -1.);
    vec4 c = q1.y * q2.zwxy * vec4(1., 1., -1., -1.);
    vec4 d = q1.z * q2.yxwz * vec4(-1., 1., 1., -1.);

    return a + b + c + d;
}

vec3 rotateVertexByQuat(vec3 v, vec4 q) {
    vec3 i = q.xyz;
    return v + 2.0 * cross(i, cross(i, v) + q.w * v);
}

vec3 rotateAbout(vec3 v, vec3 axis, float angle) {
    return rotateVertexByQuat(v, quat(axis, angle));
}
		""";

	// NB: Hardcoded to FLW Formats.POS_TEX_NORMAL
	public static String patchFlywheel(String source, ShaderType type, FlwProgramType flwType, float alphaDiscard) {
		StringTransformations transformations = new StringTransformations(source);

		if (transformations.contains("irisMain")) {
			throw new IllegalStateException("Shader already contains \"irisMain\"???");
		}

		patchCommon(transformations, type);
		addFlwAlphaTest(transformations, type, alphaDiscard);

		transformations.replaceExact("gl_TextureMatrix[0]", "mat4(1.0)");

		transformations.define("gl_ProjectionMatrix", "iris_ProjectionMatrix");

		// TODO: Add lightmap texture matrix uniforms
		// TODO: Add separate view / projection matrix uniforms (uView and uProjection) on the CPU-side

		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE,
			"uniform mat4 uView;\n" +
			"uniform mat4 uNormal;\n" +
			"uniform mat4 uProjection;\n" +
			"uniform mat4 uViewProjection;\n");

		transformations.define("gl_NormalMatrix", "iris_NormalMatrix");
		transformations.define("gl_ModelViewMatrix", "iris_ModelViewMatrix");
		transformations.define("gl_ProjectionMatrix", "iris_ProjectionMatrix");
		transformations.define("gl_ModelViewProjectionMatrix", "iris_ModelViewProjectionMatrix");

		if (type == ShaderType.VERTEX) {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE,
				"out mat3 iris_NormalMatrix;\n" +
				"out mat4 iris_ModelViewMatrix;\n" +
				"out mat4 iris_ProjectionMatrix;\n" +
				"out mat4 iris_ModelViewProjectionMatrix;\n");

			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE,
				"layout (location = 0) in vec3 _flw_v_pos;\n" +
					"layout (location = 1) in vec2 _flw_v_texCoords;\n" +
					"layout (location = 2) in vec3 _flw_v_normal;\n" +
					"layout(location = 3) in vec2 a_i_light;\n" +
					"layout(location = 4) in vec4 a_i_color;\n" +
					"layout(location = 5) in mat4 a_i_transform;\n" +
					"layout(location = 9) in mat3 a_i_normalMat;\n");

			if (flwType == FlwProgramType.ORIENTED) {
				// quaternion.glsl
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, FLW_QUAT_UTIL);
			}

			transformations.define("gl_MultiTexCoord0", "vec4(_flw_v_texCoords, 0.0, 1.0)");
			new BuiltinUniformReplacementTransformer("a_i_light").apply(transformations);

			// gl_MultiTexCoord0 and gl_MultiTexCoord1 are the only valid inputs, other texture coordinates are not valid inputs.
			for (int i = 2; i < 8; i++) {
				transformations.define("gl_MultiTexCoord" + i, " vec4(0.0, 0.0, 0.0, 1.0)");
			}

			transformations.define("gl_Color", "a_i_color");
			transformations.define("gl_Normal", "_flw_v_normal");

			transformations.define("gl_Vertex", "vec4(_flw_v_pos, 1.0)");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");

			transformations.replaceExact("main", "irisMain");
			transformations.injectLine(Transformations.InjectionPoint.END, "void main() {\n" +
				"    iris_NormalMatrix = mat3(uNormal) * a_i_normalMat;\n" +
				"    iris_ModelViewMatrix = uView * a_i_transform;\n" +
				"    iris_ProjectionMatrix = uProjection;\n" +
				"    iris_ModelViewProjectionMatrix = iris_ProjectionMatrix * iris_ModelViewMatrix;\n" +
				"    irisMain();\n" +
				"}");
		} else if (type == ShaderType.FRAGMENT) {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE,
					"in mat3 iris_NormalMatrix;\n" +
					"in mat4 iris_ModelViewMatrix;\n" +
					"in mat4 iris_ProjectionMatrix;\n" +
					"in mat4 iris_ModelViewProjectionMatrix;\n");
		}

		// TODO: Geometry shaders!!!

		applyIntelHd4000Workaround(transformations);

		return transformations.toString();
	}

	public static String patchSodium(String source, ShaderType type, AlphaTest alpha, ShaderAttributeInputs inputs, float positionScale, float positionOffset, float textureScale) {
		StringTransformations transformations = new StringTransformations(source);

		patchCommon(transformations, type);
		addAlphaTest(transformations, type, alpha);

		transformations.replaceExact("gl_TextureMatrix[0]", "mat4(1.0)");

		transformations.define("gl_ProjectionMatrix", "iris_ProjectionMatrix");

		if (type == ShaderType.VERTEX) {
			if (inputs.hasTex()) {
				transformations.define("gl_MultiTexCoord0", "vec4(_vert_tex_diffuse_coord, 0.0, 1.0)");
			} else {
				transformations.define("gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
			}

			if (inputs.hasLight()) {
				new BuiltinUniformReplacementTransformer("_vert_tex_light_coord").apply(transformations);
			} else {
				transformations.define("gl_MultiTexCoord1", "vec4(0.0, 0.0, 0.0, 1.0)");
			}

			// gl_MultiTexCoord0 and gl_MultiTexCoord1 are the only valid inputs, other texture coordinates are not valid inputs.
			for (int i = 2; i < 8; i++) {
				transformations.define("gl_MultiTexCoord" + i, " vec4(0.0, 0.0, 0.0, 1.0)");
			}
		}

		if (inputs.hasColor()) {
			// TODO: Handle the fragment shader here
			transformations.define("gl_Color", "_vert_color");

			if (type == ShaderType.VERTEX) {
			}
		} else {
			transformations.define("gl_Color", "vec4(1.0)");
		}

		if (type == ShaderType.VERTEX) {
			if (inputs.hasNormal()) {
				transformations.define("gl_Normal", "iris_Normal");
				transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec3 iris_Normal;");
			} else {
				transformations.define("gl_Normal", "vec3(0.0, 0.0, 1.0)");
			}
		}


		// TODO: Should probably add the normal matrix as a proper uniform that's computed on the CPU-side of things
		transformations.define("gl_NormalMatrix", "mat3(iris_NormalMatrix)");
		transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_NormalMatrix;");


		// TODO: All of the transformed variants of the input matrices, preferably computed on the CPU side...
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewMatrix iris_ModelViewMatrix");
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewProjectionMatrix (iris_ProjectionMatrix * iris_ModelViewMatrix)");

		if (type == ShaderType.VERTEX) {
			// TODO: Vaporwave-Shaderpack expects that vertex positions will be aligned to chunks.

			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define USE_VERTEX_COMPRESSION");
			transformations.define("VERT_POS_SCALE", String.valueOf(positionScale));
			transformations.define("VERT_POS_OFFSET", String.valueOf(positionOffset));
			transformations.define("VERT_TEX_SCALE", String.valueOf(textureScale));
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform vec3 u_RegionOffset;");

			transformations.injectLine(Transformations.InjectionPoint.DEFINES, SodiumTerrainPipeline.parseSodiumImport("#import <sodium:include/chunk_vertex.glsl>"));
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, SodiumTerrainPipeline.parseSodiumImport("#import <sodium:include/chunk_parameters.glsl>"));

			transformations.injectLine(Transformations.InjectionPoint.DEFINES, """
				// The projection matrix
				uniform mat4 iris_ProjectionMatrix;

				// The model-view matrix
				uniform mat4 iris_ModelViewMatrix;

				// The model-view-projection matrix
				#define iris_ModelViewProjectionMatrix iris_ProjectionMatrix * iris_ModelViewMatrix""");
			transformations.define("gl_Vertex", "getVertexPosition()");

			if (transformations.contains("irisMain")) {
				throw new IllegalStateException("Shader already contains \"irisMain\"???");
			}

			// Create our own main function to wrap the existing main function, so that we can run the alpha test at the
			// end.
			transformations.replaceExact("main", "irisMain");
			transformations.injectLine(Transformations.InjectionPoint.END, "void main() {\n" +
					"   _vert_init();\n" +
					"\n" +
					"	irisMain();\n" +
					"}");

			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 getVertexPosition() { return vec4(u_RegionOffset + _draw_translation + _vert_position, 1.0); }");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");
		} else {
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_ModelViewMatrix;");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform mat4 iris_ProjectionMatrix;");
		}

		applyIntelHd4000Workaround(transformations);

		return transformations.toString();
	}

	public static String patchComposite(String source, ShaderType type) {
		StringTransformations transformations = new StringTransformations(source);
		patchCommon(transformations, type);

		transformations = CompositeDepthTransformer.patch(transformations);

		// TODO: More solid way to handle texture matrices
		// TODO: Provide these values with uniforms

		for (int i = 0; i < 8; i++) {
			transformations.replaceExact("gl_TextureMatrix[" + i + "]", "mat4(1.0)");
			transformations.replaceExact("gl_TextureMatrix [" + i + "]", "mat4(1.0)");
		}

		// TODO: Other fog things


		// This is used to scale the quad projection matrix from (0, 1) to (-1, 1).
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ProjectionMatrix mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0))");

		if (type == ShaderType.VERTEX) {
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_MultiTexCoord0 vec4(UV0, 0.0, 1.0)");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec2 UV0;");

			// gl_MultiTexCoord0 is the only valid input, all other inputs
			for (int i = 1; i < 8; i++) {
				transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_MultiTexCoord" + i + " vec4(0.0, 0.0, 0.0, 1.0)");
			}
		}

		// No color attributes, the color is always solid white.
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_Color vec4(1.0, 1.0, 1.0, 1.0)");

		if (type == ShaderType.VERTEX) {
			// https://www.khronos.org/registry/OpenGL-Refpages/gl2.1/xhtml/glNormal.xml
			// The initial value of the current normal is the unit vector, (0, 0, 1).
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_Normal vec3(0.0, 0.0, 1.0)");
		}

		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_NormalMatrix mat3(1.0)");
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewMatrix mat4(1.0)");

		// TODO: All of the transformed variants of the input matrices, preferably computed on the CPU side...
		transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_ModelViewProjectionMatrix (gl_ProjectionMatrix * gl_ModelViewMatrix)");

		if (type == ShaderType.VERTEX) {
			transformations.injectLine(Transformations.InjectionPoint.DEFINES, "#define gl_Vertex vec4(Position, 1.0)");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "in vec3 Position;");
			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");
		}

		applyIntelHd4000Workaround(transformations);

		return transformations.toString();
	}

	private static void applyIntelHd4000Workaround(StringTransformations transformations) {
		// This is a driver bug workaround that seems to be needed on HD 4000 and HD 2500 drivers.
		//
		// Without this, they will see the call to ftransform() without taking into account the fact that we've defined
		// the function ourselves, and thus will allocate attribute location 0 to gl_Vertex since the driver thinks that
		// we are referencing gl_Vertex by calling the compatibility-profile ftransform() function - despite using a
		// core profile shader version where that function does not exist.
		//
		// Then, when we try to bind the attribute locations for our vertex format, the shader will fail to link.
		// By renaming the function, we avoid the driver bug here, since it no longer thinks that we're trying
		// to call the compatibility profile fransform() function.
		//
		// See: https://github.com/IrisShaders/Iris/issues/441
		//
		// Note that this happens after we've added our ftransform function - so that both all the calls and our
		// function are renamed in one go.
		transformations.define("ftransform", "iris_ftransform");
	}

	private static void addAlphaTest(StringTransformations transformations, ShaderType type, AlphaTest alpha) {
		if (type == ShaderType.FRAGMENT) {
			if (transformations.contains("irisMain")) {
				throw new IllegalStateException("Shader already contains \"irisMain\"???");
			}

			transformations.injectLine(Transformations.InjectionPoint.BEFORE_CODE, "uniform float iris_currentAlphaTest;");

			// Create our own main function to wrap the existing main function, so that we can run the alpha test at the
			// end.
			transformations.replaceExact("main", "irisMain");
			transformations.injectLine(Transformations.InjectionPoint.END, "void main() {\n" +
					"    irisMain();\n" +
					"\n" +
					alpha.toExpression("    ") +
					"}");
		}
	}

	private static void fixVersion(Transformations transformations) {
		String prefix = transformations.getPrefix();
		int split = prefix.indexOf("#version");
		String beforeVersion = prefix.substring(0, split);
		String actualVersion = prefix.substring(split + "#version".length()).trim();

		if (actualVersion.endsWith(" core")) {
			throw new IllegalStateException("Transforming a shader that is already built against the core profile???");
		}

		if (!actualVersion.startsWith("1")) {
			if (actualVersion.endsWith("compatibility")) {
				actualVersion = actualVersion.substring(0, actualVersion.length() - "compatibility".length()).trim() + " core";
			} else {
				throw new IllegalStateException("Expected \"compatibility\" after the GLSL version: #version " + actualVersion);
			}
		} else {
			actualVersion = "330 core";
		}

		beforeVersion = beforeVersion.trim();

		if (!beforeVersion.isEmpty()) {
			beforeVersion += "\n";
		}

		transformations.setPrefix(beforeVersion + "#version " + actualVersion + "\n");
	}
}
