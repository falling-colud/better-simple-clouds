package dev.bettersimpleclouds.immersion.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.culling.Frustum;

import dev.bettersimpleclouds.core.BetterSimpleCloudsConfig;
import dev.bettersimpleclouds.immersion.InteriorFillState;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.shader.SimpleCloudsShaders;
import dev.nonamecrackers2.simpleclouds.client.shader.SingleSSBOShaderInstance;

/**
 * Fixes the mottled, "see the inside and the outside at once" look of a cloud's translucent edges when you view it
 * through another cloud (worst on big clouds) - by flattening the weighted-blended OIT weight in the transparent pass.
 *
 * <p>Simple Clouds blends the soft cloud edges with weighted-blended OIT ({@code clouds_transparency.fsh}), whose
 * per-fragment weight is {@code premul.a * 3000 * pow(1 - z, 3)} - a steep cubic falloff with distance. Across a BIG
 * cloud's thick translucent fringe, a near fringe cube's weight dwarfs a far one's (tens of times over), so the
 * order-independent blend gets dominated per-patch by whichever fringe cube happens to be nearest at each pixel and
 * reads as faces darker/lighter in a noise pattern - the "inside and outside coincide" look. (Small clouds have a thin
 * fringe with little depth spread, so they're unaffected - matching the report that it's only big clouds. Face culling
 * is <i>not</i> the cause: the opaque pass leaves {@code GL_BACK} culling enabled, so the transparent cubes are already
 * single-sided.)</p>
 *
 * <p>Better Simple Clouds swaps in its own copy of the transparency shader ({@link SimpleCloudsShadersMixin}) that softens
 * that exponent toward {@code 0.5} by a {@code MicWeightFlatten} uniform. This mixin feeds that uniform each frame from
 * the config: the smoothing strength when {@link BetterSimpleCloudsConfig#fixTransparentCloudEdges()} is on, else {@code 0}
 * (byte-for-byte stock weighting). Set at the {@code HEAD} of {@code renderCloudsTransparency}, before Simple Clouds
 * applies (uploads) the shader, exactly like {@link CloudShaderMatchMixin} feeds the opaque shader.</p>
 *
 * <p>Only applied while the camera is <b>outside</b> clouds (where the artifact shows); when in-cloud immersion is on and
 * you're inside a cloud, the interior fill's translucent haze is left on stock weighting (envelopment gate). Gated on
 * {@code simpleclouds} (client) via the immersion patch's mixin plugin; {@code remap = false}.</p>
 */
@Mixin(value = SimpleCloudsRenderer.class, remap = false)
public abstract class SimpleCloudsRendererTransparencyWeightMixin {

    @Inject(
        method = "renderCloudsTransparency(Ldev/nonamecrackers2/simpleclouds/client/mesh/generator/CloudMeshGenerator;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FFFFFFLnet/minecraft/client/renderer/culling/Frustum;Z)V",
        at = @At("HEAD"))
    private static void bsc$feedTransparencyWeight(final CloudMeshGenerator generator, final PoseStack stack,
                                                   final Matrix4f projMat, final float fogStart, final float fogEnd,
                                                   final float partialTick, final float r, final float g, final float b,
                                                   final Frustum frustum, final boolean ditherFade,
                                                   final CallbackInfo ci) {
        final SingleSSBOShaderInstance shader = SimpleCloudsShaders.getCloudsTransparencyShader();
        if (shader == null)
            return;
        // Outside clouds only: inside a cloud the in-cloud immersion interior fill emits its own translucent haze cubes
        // that are meant to read as designed, so leave them on stock weighting (envelopment is > 0 only when in-cloud
        // immersion is enabled and the camera is inside a cloud).
        float flatten = BetterSimpleCloudsConfig.transparentEdgeWeightFlatten();
        if (BetterSimpleCloudsConfig.inCloudEnabled() && InteriorFillState.envelopment > 0.02F)
            flatten = 0.0F;
        shader.safeGetUniform("MicWeightFlatten").set(flatten);
    }
}
