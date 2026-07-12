package dev.bettersimpleclouds.immersion.mixin;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;

import dev.bettersimpleclouds.core.BetterSimpleCloudsConfig;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.shader.SimpleCloudsShaders;
import dev.nonamecrackers2.simpleclouds.client.shader.SingleSSBOShaderInstance;

/**
 * Feeds the "match the shader-lit scene" parameters into the cloud fragment shader each frame, so opaque clouds blend
 * into the scene under an Iris shaderpack instead of looking like flat white cut-outs. Works because Simple Clouds
 * draws its clouds with its own shader (Iris doesn't replace that custom instanced draw), and we've swapped the
 * fragment stage for ours via {@link SimpleCloudsShadersMixin}.
 *
 * <p>Set at the {@code HEAD} of {@code renderCloudsOpaque}, before Simple Clouds applies the shader (which uploads the
 * uniforms). We hand it the live scene sky colour ({@link ClientLevel#getSkyColor}) plus the configured tint/exposure/
 * saturation; when the feature is off we push the no-op defaults so the shader behaves exactly like Simple Clouds'.</p>
 */
@Mixin(value = SimpleCloudsRenderer.class, remap = false)
public abstract class CloudShaderMatchMixin {

    @Inject(
        method = "renderCloudsOpaque(Ldev/nonamecrackers2/simpleclouds/client/mesh/generator/CloudMeshGenerator;Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FFFFFFLnet/minecraft/client/renderer/culling/Frustum;Z)V",
        at = @At("HEAD"))
    private static void bsc$feedCloudShaderMatch(final CloudMeshGenerator generator, final PoseStack stack,
                                                 final Matrix4f projMat, final float fogStart, final float fogEnd,
                                                 final float partialTick, final float r, final float g, final float b,
                                                 final Frustum frustum, final boolean ditherFade,
                                                 final CallbackInfo ci) {
        final SingleSSBOShaderInstance shader = SimpleCloudsShaders.getCloudsShader();
        if (shader == null)
            return;
        final AbstractUniform tint = shader.safeGetUniform("MicSkyTint");
        final AbstractUniform brightness = shader.safeGetUniform("MicBrightness");
        final AbstractUniform saturation = shader.safeGetUniform("MicSaturation");
        final AbstractUniform edge = shader.safeGetUniform("MicEdgeFadeStrength");

        // Far-cloud fog resistance is independent of shader-match / far-edge-fade, so set it unconditionally (before
        // the early-out below) straight from config; 0 = stock.
        shader.safeGetUniform("MicFogResist").set(BetterSimpleCloudsConfig.farCloudFogResist());

        final boolean match = BetterSimpleCloudsConfig.inCloudShaderMatch();
        final boolean farEdge = BetterSimpleCloudsConfig.inCloudFarEdgeFade();
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.gameRenderer == null || (!match && !farEdge)) {
            tint.set(0.0F);          // no-op defaults -> identical to Simple Clouds' own shader
            brightness.set(1.0F);
            saturation.set(1.0F);
            edge.set(0.0F);
            return;
        }

        // The scene sky colour is the blend target for both the shader-match tint and the far-edge fade.
        final Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        final Vec3 sky = level.getSkyColor(cam, partialTick);
        shader.safeGetUniform("MicSkyColor").set((float) sky.x, (float) sky.y, (float) sky.z);

        // Shader-match: tint / exposure / saturation so clouds sit in the lit scene.
        tint.set(match ? BetterSimpleCloudsConfig.inCloudShaderSkyTint() : 0.0F);
        brightness.set(match ? BetterSimpleCloudsConfig.inCloudShaderBrightness() : 1.0F);
        saturation.set(match ? BetterSimpleCloudsConfig.inCloudShaderSaturation() : 1.0F);

        // Far-edge fade: dissolve clouds into the sky colour over the outer part of the fog range (fragment-side, so
        // it's smooth and works under shaders - no scattered geometry). fogEnd is the cloud render distance.
        if (farEdge) {
            final float fadeRange = BetterSimpleCloudsConfig.inCloudFarEdgeFadePercent() / 100.0F;
            edge.set(1.0F);
            shader.safeGetUniform("MicEdgeFadeStart").set(fogEnd * (1.0F - fadeRange));
            shader.safeGetUniform("MicEdgeFadeEnd").set(fogEnd);
        } else {
            edge.set(0.0F);
        }
    }
}
