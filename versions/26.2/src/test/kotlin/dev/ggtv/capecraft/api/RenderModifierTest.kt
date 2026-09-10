package dev.ggtv.capecraft.api

import dev.ggtv.capecraft.api.render.CapeCondition
import dev.ggtv.capecraft.api.render.CapeRenderContext
import dev.ggtv.capecraft.api.render.CapeRenderModifier
import dev.ggtv.capecraft.api.render.CapeRenderModifierRegistry
import dev.ggtv.kjen.Value
import dev.ggtv.koren.EmptyWorldContext
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class RenderModifierTest {

    private lateinit var registry: CapeRenderModifierRegistry

    @BeforeEach
    fun setup() {
        registry = CapeRenderModifierRegistry()
    }

    @Test
    fun `modifier without condition always applies`() {
        var beforeCalled = false
        var afterCalled = false

        registry.register(object : CapeRenderModifier {
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                beforeCalled = true
                return null
            }
            override fun afterRender(ctx: CapeRenderContext) {
                afterCalled = true
            }
        })

        val ctx = CapeRenderContext(
            uuid = "test",
            textureId = Identifier.fromNamespaceAndPath("test", "tex"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 0, 0, 255),
        )
        val tex = registry.applyBefore(ctx, ctx.textureId, EmptyWorldContext)
        registry.applyAfter(ctx, EmptyWorldContext)

        assertTrue(beforeCalled)
        assertTrue(afterCalled)
        assertEquals(ctx.textureId, tex)
    }

    @Test
    fun `modifier can replace texture`() {
        val newTex = Identifier.fromNamespaceAndPath("test", "new_tex")
        registry.register(object : CapeRenderModifier {
            override fun beforeRender(ctx: CapeRenderContext): Identifier = newTex
        })

        val ctx = CapeRenderContext(
            uuid = "test",
            textureId = Identifier.fromNamespaceAndPath("test", "old_tex"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 0, 0, 255),
        )
        val result = registry.applyBefore(ctx, ctx.textureId, EmptyWorldContext)
        assertEquals(newTex, result)
    }

    @Test
    fun `modifier with matching condition applies`() {
        var called = false
        registry.register(object : CapeRenderModifier {
            override val condition = CapeCondition(WorldRoot.WEATHER, "condition", "rain")
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                called = true
                return null
            }
        })

        val world = object : WorldContext {
            override fun field(root: WorldRoot, field: String, path: String): Value =
                if (root == WorldRoot.WEATHER && field == "condition") Value.VStr("rain")
                else Value.VStr("unknown")
        }

        val ctx = CapeRenderContext(
            uuid = "test",
            textureId = Identifier.fromNamespaceAndPath("test", "tex"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 0, 0, 255),
        )
        registry.applyBefore(ctx, ctx.textureId, world)
        assertTrue(called)
    }

    @Test
    fun `modifier with non-matching condition skipped`() {
        var called = false
        registry.register(object : CapeRenderModifier {
            override val condition = CapeCondition(WorldRoot.WEATHER, "condition", "rain")
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                called = true
                return null
            }
        })

        val world = object : WorldContext {
            override fun field(root: WorldRoot, field: String, path: String): Value =
                if (root == WorldRoot.WEATHER && field == "condition") Value.VStr("clear")
                else Value.VStr("unknown")
        }

        val ctx = CapeRenderContext(
            uuid = "test",
            textureId = Identifier.fromNamespaceAndPath("test", "tex"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 0, 0, 255),
        )
        registry.applyBefore(ctx, ctx.textureId, world)
        assertFalse(called)
    }

    @Test
    fun `exception in modifier does not crash`() {
        registry.register(object : CapeRenderModifier {
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                throw RuntimeException("boom")
            }
        })

        val ctx = CapeRenderContext(
            uuid = "test",
            textureId = Identifier.fromNamespaceAndPath("test", "tex"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 0, 0, 255),
        )
        // Should not throw
        val tex = registry.applyBefore(ctx, ctx.textureId, EmptyWorldContext)
        assertEquals(ctx.textureId, tex)
    }

    @Test
    fun `multiple modifiers applied in order`() {
        val order = mutableListOf<String>()
        registry.register(object : CapeRenderModifier {
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                order.add("first")
                return null
            }
        })
        registry.register(object : CapeRenderModifier {
            override fun beforeRender(ctx: CapeRenderContext): Identifier? {
                order.add("second")
                return null
            }
        })

        val ctx = CapeRenderContext(
            uuid = "test",
            textureId = Identifier.fromNamespaceAndPath("test", "tex"),
            matrices = com.mojang.blaze3d.vertex.PoseStack(),
            light = 15,
            outlineColor = intArrayOf(255, 0, 0, 255),
        )
        registry.applyBefore(ctx, ctx.textureId, EmptyWorldContext)
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `registry size tracks modifiers`() {
        assertEquals(0, registry.size)
        registry.register(object : CapeRenderModifier {})
        assertEquals(1, registry.size)
        registry.register(object : CapeRenderModifier {})
        assertEquals(2, registry.size)
    }
}
