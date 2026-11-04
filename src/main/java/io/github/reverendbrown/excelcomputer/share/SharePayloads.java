package io.github.reverendbrown.excelcomputer.share;

//? if >=1.21 {
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// The payload types that carry ShareProtocol messages on 1.21+ (Fabric and NeoForge).
public final class SharePayloads {
    private SharePayloads() {
    }

    public record ToServer(byte[] data) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(ShareNetwork.TO_SERVER_ID);
        public static final StreamCodec<FriendlyByteBuf, ToServer> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeByteArray(payload.data),
                buf -> new ToServer(buf.readByteArray(ShareProtocol.MAX_MESSAGE_BYTES))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ToClient(byte[] data) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(ShareNetwork.TO_CLIENT_ID);
        public static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeByteArray(payload.data),
                buf -> new ToClient(buf.readByteArray(ShareProtocol.MAX_MESSAGE_BYTES))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
//?}
