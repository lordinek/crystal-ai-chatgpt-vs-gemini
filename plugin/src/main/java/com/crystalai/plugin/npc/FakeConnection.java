package com.crystalai.plugin.npc;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A network connection with no client behind it. Outgoing packets are swallowed
 * (instead of queued forever), but handed to an optional sink so the NPC can react
 * to packets a real client would act on (e.g. velocity after a melee hit).
 *
 * <p>1.20.5+: protocol switches set the listener and then write a pipeline reconfiguration
 * task into the channel; an empty embedded channel just drops it, which is what we want.
 */
public final class FakeConnection extends Connection {

    private Consumer<Packet<?>> packetSink = packet -> { };

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    public void setPacketSink(Consumer<Packet<?>> packetSink) {
        this.packetSink = packetSink;
    }

    @Override
    public void send(Packet<?> packet) {
        packetSink.accept(packet);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
        packetSink.accept(packet);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        packetSink.accept(packet);
    }
}
