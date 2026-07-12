#version 430

// Better Simple Clouds: a copy of Simple Clouds' clouds.fsh with a small "sit nicely in a shader-lit scene" pass added
// at the end. Better Simple Clouds registers the cloud shader as bettersimpleclouds:clouds (reusing Simple Clouds' own
// vertex shader, so the SSBO geometry expansion is unchanged) so this fragment shader runs instead of the stock one.
// All additions are inert at their defaults (MicSkyTint = 0, MicBrightness = 1, MicSaturation = 1) -> identical to
// Simple Clouds. The patch feeds the live values + the scene sky colour each frame (CloudShaderMatchMixin).
// If Simple Clouds updates clouds.fsh, re-copy it and re-apply the marked block.

uniform sampler2D BayerMatrixSampler;

uniform vec4 ColorModulator;
uniform float DitherScale;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

// === Better Simple Clouds additions ===
uniform vec3 MicSkyColor = vec3(0.0); // scene sky/atmosphere colour clouds are tinted toward
uniform float MicSkyTint = 0.0;       // 0 = off; how much distant-ish clouds pick up the sky colour
uniform float MicBrightness = 1.0;    // overall exposure multiplier (lower = less washed-out under shaders)
uniform float MicSaturation = 1.0;    // colour saturation (1 = unchanged)
// Soft far edge: additionally fade clouds into the sky colour as they approach the render horizon, so they dissolve
// into the sky instead of hard-cutting. Done here (fragment) rather than by thinning geometry, so it's smooth and
// works under shaders. Inert when MicEdgeFadeStrength = 0.
uniform float MicEdgeFadeStrength = 0.0;
uniform float MicEdgeFadeStart = 0.0;
uniform float MicEdgeFadeEnd = 0.0;
// EXPERIMENTAL far-cloud fog resistance: Simple Clouds fades distant clouds into the fog colour (washing them out near
// the horizon). This holds that back so far/edge clouds stay vivid. 0 = stock fade; 1 = clouds keep full colour to the
// edge (can look like they don't sit in the haze). Inert at 0.
uniform float MicFogResist = 0.0;

in vec4 vertexColor;
in float fogDistance;

out vec4 fragColor;

void main()
{
	float fade = ColorModulator.a;
	float r = texture(BayerMatrixSampler, gl_FragCoord.xy * DitherScale).r;
	if (fade < r)
		discard;

	vec4 color = vertexColor * vec4(ColorModulator.rgb, 1.0);
	// Better Simple Clouds: optionally hold back Simple Clouds' distance-fog wash so far/edge clouds stay vivid.
	color = mix(color, FogColor, smoothstep(FogStart, FogEnd, fogDistance) * (1.0 - clamp(MicFogResist, 0.0, 1.0)));

	// === Better Simple Clouds: match the shader-lit scene a little better (inert at defaults). ===
	// Aerial perspective: tint toward the sky colour with DISTANCE only, so near clouds keep their true colour and
	// don't go patchy (an earlier luminance-weighted tint exaggerated per-face contrast into grey cubes).
	if (MicSkyTint > 0.0)
	{
		float aerial = smoothstep(0.0, FogEnd, fogDistance);
		color.rgb = mix(color.rgb, MicSkyColor, clamp(MicSkyTint * aerial, 0.0, 1.0));
	}
	if (MicSaturation != 1.0)
	{
		float grey = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
		color.rgb = mix(vec3(grey), color.rgb, MicSaturation);
	}
	color.rgb *= MicBrightness;

	// === Better Simple Clouds: soft far edge - fade clouds toward the SCENE FOG colour (the same colour Simple Clouds
	// itself fades distant clouds to, just above) over the outer render distance, so they melt smoothly into the
	// horizon haze. Uses FogColor, NOT the camera sky colour: it matches the real horizon so there are no flat grey
	// patches, and it's a plain colour blend so there's no dithered/pixelated look. ===
	if (MicEdgeFadeStrength > 0.0 && MicEdgeFadeEnd > MicEdgeFadeStart)
	{
		float edge = smoothstep(MicEdgeFadeStart, MicEdgeFadeEnd, fogDistance);
		color.rgb = mix(color.rgb, FogColor.rgb, clamp(edge * MicEdgeFadeStrength, 0.0, 1.0));
	}

	fragColor = vec4(color.rgb, 1.0);
}
