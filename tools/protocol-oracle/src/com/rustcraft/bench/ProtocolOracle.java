package com.rustcraft.bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.world.GameType;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldType;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;

public class ProtocolOracle {
    static final String WRITE_METHOD = "func_148840_b";

    public static void main(String[] args) throws Exception {
        // Initialize vanilla registries so blocks/items can be looked up
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);

        StringBuilder out = new StringBuilder();

        // 1. Handshake
        Object hs = Class.forName("net.minecraft.network.handshake.client.C00Handshake").newInstance();
        set(hs, "field_149600_a", 340);
        set(hs, "field_149598_b", "localhost");
        set(hs, "field_149599_c", 25565);
        set(hs, "field_149597_d", EnumConnectionState.LOGIN);
        dump(out, "Handshake", EnumConnectionState.HANDSHAKING, EnumPacketDirection.SERVERBOUND, 0, hs);

        // 2. Status ServerQuery
        Object sq = Class.forName("net.minecraft.network.status.client.CPacketServerQuery").newInstance();
        dump(out, "StatusRequest", EnumConnectionState.STATUS, EnumPacketDirection.SERVERBOUND, 0, sq);

        // 3. Status Ping
        Object ping = Class.forName("net.minecraft.network.status.client.CPacketPing").newInstance();
        set(ping, "field_149290_a", 0x1122334455L);
        dump(out, "Ping", EnumConnectionState.STATUS, EnumPacketDirection.SERVERBOUND, 1, ping);

        // 4. Status Pong
        Object pong = Class.forName("net.minecraft.network.status.server.SPacketPong").newInstance();
        set(pong, "field_149293_a", 0x1122334455L);
        dump(out, "Pong", EnumConnectionState.STATUS, EnumPacketDirection.CLIENTBOUND, 1, pong);

        // 5. LoginStart
        Object ls = Class.forName("net.minecraft.network.login.client.CPacketLoginStart").newInstance();
        set(ls, "field_149305_a", new GameProfile(null, "TestUser"));
        dump(out, "LoginStart", EnumConnectionState.LOGIN, EnumPacketDirection.SERVERBOUND, 0, ls);

        // 6. EncryptionRequest
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(512);
        KeyPair kp = kpg.generateKeyPair();
        Object er = Class.forName("net.minecraft.network.login.server.SPacketEncryptionRequest").newInstance();
        set(er, "field_149612_a", "");
        set(er, "field_149610_b", kp.getPublic());
        set(er, "field_149611_c", new byte[]{1, 2, 3, 4});
        dump(out, "EncryptionRequest", EnumConnectionState.LOGIN, EnumPacketDirection.CLIENTBOUND, 1, er);

        // 7. EncryptionResponse
        Object eresp = Class.forName("net.minecraft.network.login.client.CPacketEncryptionResponse").newInstance();
        set(eresp, "field_149302_a", new byte[]{0x10, 0x20, 0x30});
        set(eresp, "field_149301_b", new byte[]{0x01, 0x02, 0x03, 0x04});
        dump(out, "EncryptionResponse", EnumConnectionState.LOGIN, EnumPacketDirection.SERVERBOUND, 1, eresp);

        // 8. SetCompression
        Object sc = Class.forName("net.minecraft.network.login.server.SPacketEnableCompression").newInstance();
        set(sc, "field_179733_a", 256);
        dump(out, "SetCompression", EnumConnectionState.LOGIN, EnumPacketDirection.CLIENTBOUND, 3, sc);

        // 9. LoginSuccess
        Object lsucc = Class.forName("net.minecraft.network.login.server.SPacketLoginSuccess").newInstance();
        set(lsucc, "field_149602_a", new GameProfile(UUID.nameUUIDFromBytes("TestUser".getBytes()), "TestUser"));
        dump(out, "LoginSuccess", EnumConnectionState.LOGIN, EnumPacketDirection.CLIENTBOUND, 2, lsucc);

        // 10. Login Disconnect
        Object ldisc = Class.forName("net.minecraft.network.login.server.SPacketDisconnect").newInstance();
        set(ldisc, "field_149605_a", new TextComponentString("Outdated server"));
        dump(out, "LoginDisconnect", EnumConnectionState.LOGIN, EnumPacketDirection.CLIENTBOUND, 0, ldisc);

        // 11. Play KeepAlive
        Object ka = Class.forName("net.minecraft.network.play.client.CPacketKeepAlive").newInstance();
        set(ka, "field_149461_a", 0xAABBCCDDL);
        dump(out, "KeepAlive", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 11, ka);

        // 12. Play ChatMessage
        Object chat = Class.forName("net.minecraft.network.play.client.CPacketChatMessage").newInstance();
        set(chat, "field_149440_a", "hello world");
        dump(out, "ChatMessage", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 2, chat);

        // 13. Movement - CPacketPlayer$Position
        Object pos = Class.forName("net.minecraft.network.play.client.CPacketPlayer$Position").newInstance();
        set(pos, "field_149479_a", 100.5);
        set(pos, "field_149477_b", 64.0);
        set(pos, "field_149478_c", -200.5);
        set(pos, "field_149474_g", true);
        dump(out, "PlayerPosition", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 13, pos);

        // 14. Movement - CPacketPlayer$Rotation
        Object rot = Class.forName("net.minecraft.network.play.client.CPacketPlayer$Rotation").newInstance();
        set(rot, "field_149476_e", 90.0f);
        set(rot, "field_149473_f", 0.0f);
        set(rot, "field_149474_g", false);
        dump(out, "PlayerRotation", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 15, rot);

        // 15. Movement - CPacketPlayer$PositionRotation
        Object posrot = Class.forName("net.minecraft.network.play.client.CPacketPlayer$PositionRotation").newInstance();
        set(posrot, "field_149479_a", 10.0);
        set(posrot, "field_149477_b", 70.0);
        set(posrot, "field_149478_c", 30.0);
        set(posrot, "field_149476_e", 180.0f);
        set(posrot, "field_149473_f", -45.0f);
        set(posrot, "field_149474_g", true);
        dump(out, "PlayerPositionRotation", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 14, posrot);

        // 16. CPacketUseEntity (interact)
        Object useEnt = Class.forName("net.minecraft.network.play.client.CPacketUseEntity").newInstance();
        set(useEnt, "field_149567_a", 42); // entity id
        Class<?> actionEnum = Class.forName("net.minecraft.network.play.client.CPacketUseEntity$Action");
        Object interactAction = actionEnum.getField("INTERACT").get(null);
        set(useEnt, "field_149566_b", interactAction);
        set(useEnt, "field_186995_d", EnumHand.MAIN_HAND);
        dump(out, "UseEntityInteract", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 10, useEnt);

        // 17. Block Interaction - CPacketPlayerTryUseItemOnBlock
        Object useBlock = Class.forName("net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock").newInstance();
        set(useBlock, "field_179725_b", new BlockPos(10, 64, -20));
        set(useBlock, "field_149579_d", EnumFacing.UP);
        set(useBlock, "field_187027_c", EnumHand.MAIN_HAND);
        set(useBlock, "field_149577_f", 0.5f);
        set(useBlock, "field_149578_g", 1.0f);
        set(useBlock, "field_149584_h", 0.5f);
        dump(out, "PlayerTryUseItemOnBlock", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 31, useBlock);

        // 18. Window Click - CPacketClickWindow
        Object clickWin = Class.forName("net.minecraft.network.play.client.CPacketClickWindow").newInstance();
        set(clickWin, "field_149554_a", 0); // windowId
        set(clickWin, "field_149552_b", 36); // slot
        set(clickWin, "field_149553_c", 0); // mouse button
        set(clickWin, "field_149550_d", (short)1); // action number
        Class<?> clickTypeEnum = Class.forName("net.minecraft.inventory.ClickType");
        set(clickWin, "field_149549_f", clickTypeEnum.getField("PICKUP").get(null));
        Object emptyItem = Class.forName("net.minecraft.item.ItemStack").getField("field_190927_a").get(null);
        set(clickWin, "field_149551_e", emptyItem);
        dump(out, "ClickWindow", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 7, clickWin);

        // 19. CustomPayload - CPacketCustomPayload
        Object cp = Class.forName("net.minecraft.network.play.client.CPacketCustomPayload").newInstance();
        set(cp, "field_149562_a", "MC|Brand");
        PacketBuffer pbData = new PacketBuffer(Unpooled.buffer());
        pbData.func_180714_a("vanilla");
        set(cp, "field_149561_c", pbData);
        dump(out, "CustomPayloadBrand", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 9, cp);

        // 20. BlockChange - SPacketBlockChange
        Object bc = Class.forName("net.minecraft.network.play.server.SPacketBlockChange").newInstance();
        set(bc, "field_179828_a", new BlockPos(100, 65, 200));
        Method getBlock = Class.forName("net.minecraft.block.Block").getMethod("func_149729_e", int.class);
        Object stoneBlock = getBlock.invoke(null, 1);
        Method getDefaultState = Class.forName("net.minecraft.block.Block").getMethod("func_176223_P");
        set(bc, "field_148883_d", getDefaultState.invoke(stoneBlock));
        dump(out, "BlockChange", EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 11, bc);

        // 21. JoinGame - SPacketJoinGame
        Object jg = Class.forName("net.minecraft.network.play.server.SPacketJoinGame").newInstance();
        set(jg, "field_149206_a", 1001); // entityId
        set(jg, "field_149204_b", false); // hardcore
        set(jg, "field_149205_c", GameType.SURVIVAL);
        set(jg, "field_149202_d", 0); // dimension
        set(jg, "field_149203_e", EnumDifficulty.NORMAL);
        set(jg, "field_149200_f", 20); // maxPlayers
        Object defaultWorldType = Class.forName("net.minecraft.world.WorldType").getField("field_77137_b").get(null);
        set(jg, "field_149201_g", defaultWorldType);
        set(jg, "field_179745_h", false); // reducedDebugInfo
        dump(out, "JoinGame", EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 35, jg);

        // 22. CustomPayload - SPacketCustomPayload (ServerHello)
        Object spcp = Class.forName("net.minecraft.network.play.server.SPacketCustomPayload").newInstance();
        set(spcp, "field_149172_a", "FML|HS");
        PacketBuffer pbHs = new PacketBuffer(Unpooled.buffer());
        pbHs.writeByte(0); // ServerHello discriminator
        pbHs.writeByte(2); // protocol version
        pbHs.writeInt(0); // dimension override
        set(spcp, "field_149171_b", pbHs);
        dump(out, "ServerHelloCustomPayload", EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 24, spcp);

        // 23. Status Response - SPacketServerInfo
        Object sinfo = Class.forName("net.minecraft.network.status.server.SPacketServerInfo").newInstance();
        Object statusResp = Class.forName("net.minecraft.network.ServerStatusResponse").newInstance();
        Method setDesc = statusResp.getClass().getMethod("func_151315_a", net.minecraft.util.text.ITextComponent.class);
        setDesc.invoke(statusResp, new TextComponentString("A Minecraft Server"));
        set(sinfo, "field_149296_b", statusResp);
        dump(out, "StatusResponse", EnumConnectionState.STATUS, EnumPacketDirection.CLIENTBOUND, 0, sinfo);

        // 24. Inventory Update - SPacketSetSlot
        Object setSlot = Class.forName("net.minecraft.network.play.server.SPacketSetSlot").newInstance();
        set(setSlot, "field_149179_a", 0); // window 0
        set(setSlot, "field_149177_b", 36); // slot 36
        set(setSlot, "field_149178_c", emptyItem);
        dump(out, "SetSlot", EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 22, setSlot);

        // 25. NBT-bearing Packet - SPacketUpdateTileEntity
        Object ute = Class.forName("net.minecraft.network.play.server.SPacketUpdateTileEntity").newInstance();
        set(ute, "field_179824_a", new BlockPos(10, 20, 30));
        set(ute, "field_148859_d", 1); // metadata/type
        NBTTagCompound tag = new NBTTagCompound();
        tag.func_74778_a("id", "minecraft:chest"); // setString
        tag.func_74768_a("x", 10); // setInteger
        tag.func_74768_a("y", 20);
        tag.func_74768_a("z", 30);
        set(ute, "field_148860_e", tag);
        dump(out, "UpdateTileEntity", EnumConnectionState.PLAY, EnumPacketDirection.CLIENTBOUND, 9, ute);

        // 26. Forge Handshake - ClientHello CustomPayload
        Object chcp = Class.forName("net.minecraft.network.play.client.CPacketCustomPayload").newInstance();
        set(chcp, "field_149562_a", "FML|HS");
        PacketBuffer pbCh = new PacketBuffer(Unpooled.buffer());
        pbCh.writeByte(1); // ClientHello discriminator
        pbCh.writeByte(2); // protocol version
        set(chcp, "field_149561_c", pbCh);
        dump(out, "ClientHelloCustomPayload", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 9, chcp);

        // 27. Forge Handshake - HandshakeAck CustomPayload
        Object ackcp = Class.forName("net.minecraft.network.play.client.CPacketCustomPayload").newInstance();
        set(ackcp, "field_149562_a", "FML|HS");
        PacketBuffer pbAck = new PacketBuffer(Unpooled.buffer());
        pbAck.writeByte(255); // HandshakeAck discriminator (-1)
        pbAck.writeByte(2); // phase 2
        set(ackcp, "field_149561_c", pbAck);
        dump(out, "HandshakeAckCustomPayload", EnumConnectionState.PLAY, EnumPacketDirection.SERVERBOUND, 9, ackcp);

        System.out.print(out);
    }

    static void set(Object obj, String field, Object val) throws Exception {
        Field f = null;
        Class<?> cur = obj.getClass();
        while (cur != null && f == null) {
            try {
                f = cur.getDeclaredField(field);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        if (f == null) throw new NoSuchFieldException("Field " + field + " in " + obj.getClass());
        f.setAccessible(true);
        f.set(obj, val);
    }

    static void dump(StringBuilder out, String name, EnumConnectionState st,
                     EnumPacketDirection dir, int id, Object pkt) throws Exception {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.func_150787_b(id);
        Method m = pkt.getClass().getMethod(WRITE_METHOD, PacketBuffer.class);
        m.invoke(pkt, buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        out.append(name).append('|').append(st.name()).append('|').append(dir.name())
           .append('|').append(id).append('|').append(bytes.length).append('|').append(hex).append('\n');
        buf.release();
    }
}
