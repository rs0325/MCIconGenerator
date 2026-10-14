package io.github.rs0325.mcicon.worker;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class RendererCleanupTest {
    @TempDir Path directory;
    @Test void restoresActualBindingsAfterTargetDestructionAndKeepsDrawFailure() throws Exception {verify(false,false);}
    @Test void restoresActualBindingsAfterTargetDestructionAndKeepsReadbackFailure() throws Exception {verify(true,false);}
    @Test void restores1144BindingsAndViewportAfterDrawFailure() throws Exception {verify(false,true);}
    @Test void restores1144BindingsAndViewportAfterReadbackFailure() throws Exception {verify(true,true);}
    @Test void restoresNamed261BindingsAndViewportAfterDrawFailure() throws Exception {for(String version:new String[]{"26.1","26.1.1","26.1.2"})verify(false,false,version);}
    @Test void restoresNamed261BindingsAndViewportAfterReadbackFailure() throws Exception {for(String version:new String[]{"26.1","26.1.1","26.1.2"})verify(true,false,version);}
    private void verify(boolean readback,boolean oldApi) throws Exception {
        verify(readback,oldApi,(String)null);
    }
    private void verify(boolean readback,boolean oldApi,String namedVersion) throws Exception {
        boolean named26=namedVersion!=null;
        JsonObject previous=RenderAgent.config;JsonObject config=new JsonObject();config.addProperty("version",named26?namedVersion:oldApi?"1.14.4":"1.21.4");config.addProperty("profile",named26?"gui-deferred":"test");RenderAgent.config=config;
        try {
            State state=new State();state.oldApi=oldApi;if(readback){state.draw=state.read=0;state.cachedDraw=state.cachedRead=0;}Gl.state=state;
            if(named26)com.mojang.blaze3d.opengl.GlStateManager.reset();
            Path mapping=directory.resolve("mappings.txt");
            String prefix="io.github.rs0325.mcicon.worker.RendererCleanupTest$";
            String stateMapping=named26?"":"com.mojang.blaze3d.platform.GlStateManager -> "+prefix+(oldApi?"OldStateManager":"StateManager")+":\n";
            String namedRegistries=named26?"net.minecraft.core.registries.BuiltInRegistries -> "+prefix+"Registry:\n    java.lang.Object ITEM -> ITEM\n    java.lang.Object BLOCK -> BLOCK\n":"";
            String namedIdentifier=named26?"net.minecraft.resources.Identifier -> "+prefix+"Identifier:\n    java.lang.Object parse(java.lang.String) -> parse\n":"";
            Files.write(mapping,("net.minecraft.core.Registry -> "+prefix+"Registry:\n    java.lang.Object ITEM -> ITEM\n    java.lang.Object BLOCK -> BLOCK\n    java.lang.Object getValue(java.lang.Object) -> getValue\n    java.lang.String getKey(java.lang.Object) -> getKey\n"+namedRegistries+"net.minecraft.resources.ResourceLocation -> "+prefix+"Identifier:\n    java.lang.Object parse(java.lang.String) -> parse\n"+namedIdentifier+"net.minecraft.world.item.Item -> "+prefix+"Item:\nnet.minecraft.client.Minecraft -> "+prefix+"Minecraft:\n    java.lang.Object mainRenderTarget -> mainRenderTarget\ncom.mojang.blaze3d.pipeline.RenderTarget -> "+prefix+"Target:\n"+stateMapping+"org.lwjgl.opengl.GL11 -> "+prefix+"Gl:\norg.lwjgl.opengl.GL30 -> "+prefix+"Gl:\n").getBytes(StandardCharsets.UTF_8));
            Symbols symbols=new Symbols(mapping);Minecraft minecraft=new Minecraft(state);
            if(named26)assertFalse(symbols.hasClass("com.mojang.blaze3d.opengl.GlStateManager"));
            IllegalStateException failure=new IllegalStateException("draw/readback fault");
            ModernRenderer renderer=new ModernRenderer(symbols,minecraft) {
                protected Object createTarget(int size,boolean old) {state.draw=state.read=0;return state.target;}
                protected void clearTarget(Object target,boolean old) {}
                protected void bindTarget(Object target) {state.draw=state.read=42;state.viewport=new int[]{0,0,64,64};state.cachedViewport=state.viewport.clone();}
                protected Object setupView(boolean old) {state.viewRestored=false;return Boolean.TRUE;}
                protected void restoreView(Object view) {state.viewRestored=true;}
                protected void setupLighting() {}
                protected void drawItem(Object stack) {if(!readback)throw failure;}
                protected ByteBuffer readPixels(Object target,int size) {throw failure;}
            };
            JsonObject request=new JsonObject();request.addProperty("id","minecraft:stone");request.addProperty("size",64);
            assertSame(failure,assertThrows(IllegalStateException.class,()->renderer.render(request)));
            assertTrue(state.destroyed);assertTrue(state.viewRestored);assertSame(state.original,minecraft.mainRenderTarget);
            assertEquals(readback?0:23,state.draw);assertEquals(readback?0:24,state.read);
            assertArrayEquals(new int[]{2,4,100,120},state.viewport);
            assertTrue(state.destroyedBeforeRestore);
            if(!oldApi&&!named26){assertEquals(state.draw,state.cachedDraw);assertEquals(state.read,state.cachedRead);}
            if(!named26)assertArrayEquals(state.viewport,state.cachedViewport);
            if(named26){assertEquals(readback?0:23,com.mojang.blaze3d.opengl.GlStateManager.draw);assertEquals(readback?0:24,com.mojang.blaze3d.opengl.GlStateManager.read);}
            if(oldApi)Gl.glBindFramebuffer(36160,0);else if(named26)com.mojang.blaze3d.opengl.GlStateManager._glBindFramebuffer(36160,0);else StateManager._glBindFramebuffer(36160,0);
            assertEquals(0,state.draw);assertEquals(0,state.read);
            if(named26){assertEquals(0,com.mojang.blaze3d.opengl.GlStateManager.draw);assertEquals(0,com.mojang.blaze3d.opengl.GlStateManager.read);}
        } finally {RenderAgent.config=previous;Gl.state=null;if(named26)com.mojang.blaze3d.opengl.GlStateManager.reset();}
    }
    private static final class State {
        final Object original=new Object();final Target target=new Target();
        int draw=23,read=24,cachedDraw=0,cachedRead=0;int[] cachedViewport={2,4,100,120};int[] viewport={2,4,100,120};boolean destroyed,viewRestored=true,destroyedBeforeRestore,oldApi;
    }
    public static final class Minecraft {
        public Object mainRenderTarget;
        Minecraft(State state){mainRenderTarget=state.original;}
    }
    public static final class Registry {
        public static final Registry ITEM=new Registry(),BLOCK=new Registry();
        public Object getValue(Object id){return new Item();}
        public String getKey(Object item){return "minecraft:stone";}
    }
    public static final class Identifier {public static Object parse(String name){return name;}}
    public static final class Item {public Object getDefaultInstance(){return this;}}
    public static final class Target {public void destroyBuffers(){Gl.state.destroyed=true;if(Gl.state.oldApi)Gl.glBindFramebuffer(36160,0);else StateManager._glBindFramebuffer(36160,0);}}
    public static final class OldStateManager {
        public static void viewport(int x,int y,int width,int height){Gl.state.cachedViewport=new int[]{x,y,width,height};Gl.glViewport(x,y,width,height);}
    }
    public static final class StateManager {
        public static void _glBindFramebuffer(int target,int framebuffer){State state=Gl.state;boolean changed=false;if(target!=36008){changed|=state.cachedDraw!=framebuffer;state.cachedDraw=framebuffer;}if(target!=36009){changed|=state.cachedRead!=framebuffer;state.cachedRead=framebuffer;}if(changed){if(target==36160){state.draw=state.read=framebuffer;}else Gl.glBindFramebuffer(target,framebuffer);}}
        public static void _viewport(int x,int y,int width,int height){Gl.state.cachedViewport=new int[]{x,y,width,height};Gl.glViewport(x,y,width,height);}
    }
    public static final class Gl {
        private static State state;
        public static void glGetIntegerv(int parameter,IntBuffer buffer){for(int i=0;i<4;i++)buffer.put(i,state.viewport[i]);}
        public static int glGetInteger(int parameter){return parameter==36006?state.draw:state.read;}
        public static void glBindFramebuffer(int target,int framebuffer){state.destroyedBeforeRestore=state.destroyed;if(target==36160){state.draw=state.read=framebuffer;}else if(target==36009)state.draw=framebuffer;else state.read=framebuffer;}
        public static void glViewport(int x,int y,int width,int height){state.viewport=new int[]{x,y,width,height};}
    }
}
