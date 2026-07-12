//https://jcgt.org/published/0002/02/09/paper.pdf and http://casual-effects.blogspot.com/2015/03/implemented-weighted-blended-order.html

// Better Simple Clouds: a copy of Simple Clouds' clouds_transparency.fsh with ONE change - the weighted-blended OIT
// weight's depth falloff is made tunable (MicWeightFlatten). Better Simple Clouds registers this as
// bettersimpleclouds:clouds_transparency (reusing Simple Clouds' own vertex shader, so the SSBO cube expansion is
// unchanged) and swaps it in for the stock transparency shader (SimpleCloudsShadersMixin). Inert at
// MicWeightFlatten = 0 -> byte-for-byte Simple Clouds' weighting. The patch feeds the live value each frame
// (SimpleCloudsRendererTransparencyWeightMixin). If Simple Clouds updates clouds_transparency.fsh, re-copy it and
// re-apply the marked block.

#version 430

uniform sampler2D BayerMatrixSampler;

uniform vec4 ColorModulator;
uniform float DitherScale;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

// === Better Simple Clouds addition ===
// 0 = stock Simple Clouds weighting; 1 = fully flat depth weighting. See below.
uniform float MicWeightFlatten = 0.0;

in vec4 vertexColor;
in float fogDistance;
in float vertexDistance;

layout(location = 0) out vec4 accumColor;
layout(location = 1) out float revealage;

void main()
{
	float fade = ColorModulator.a;
	float r = texture(BayerMatrixSampler, gl_FragCoord.xy * DitherScale).r;
	if (fade < r)
		discard;

	vec4 color = vertexColor * vec4(ColorModulator.rgb, 1.0);
	color = mix(color, FogColor, smoothstep(FogStart, FogEnd, fogDistance));

	vec4 premul = vec4(color.r * color.a, color.g * color.a, color.b * color.a, color.a);

	float z = min(vertexDistance / 1000.0, 1.0);
	// === Better Simple Clouds: flatten the OIT weight's depth term. Stock Simple Clouds uses pow(1 - z, 3.0), which makes
	// a NEAR fringe cube's weight dwarf a FAR one's; across a big cloud's thick translucent fringe the order-independent
	// blend is then dominated per-patch and reads as faces darker/lighter in a noise pattern. Softening the exponent
	// (3.0 -> 0.5) makes near and far fringe contribute comparably so the blend is smooth. MicWeightFlatten = 0 keeps the
	// stock exponent exactly. ===
	float micExp = mix(3.0, 0.5, clamp(MicWeightFlatten, 0.0, 1.0));
	float weight = max(premul.a * 3000.0 * pow(1.0 - z, micExp), 0.01);

    accumColor = premul * weight;
    revealage = premul.a;
}
