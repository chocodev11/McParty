package dev.epicc.hologram;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketTextDisplayRenderer implements HologramRenderer {

    private static final int FIRST_FAKE_ENTITY_ID = Integer.MAX_VALUE / 2;
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(FIRST_FAKE_ENTITY_ID);

    @Override
    public HologramRenderer.Handle show(Player player, HologramView view) {
        HologramRenderer.Handle handle = new HologramRenderer.Handle(NEXT_ENTITY_ID.getAndIncrement(), UUID.randomUUID());
        HologramLocation location = view.definition().location();
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                handle.entityId(),
                java.util.Optional.of(handle.uuid()),
                EntityTypes.TEXT_DISPLAY,
                new Vector3d(location.x(), location.y(), location.z()),
                location.pitch(),
                location.yaw(),
                location.yaw(),
                0,
                java.util.Optional.empty()
        );
        send(player, spawn);
        update(player, handle, view);
        return handle;
    }

    @Override
    public void update(Player player, HologramRenderer.Handle handle, HologramView view) {
        send(player, new WrapperPlayServerEntityMetadata(handle.entityId(), metadata(view)));
    }

    @Override
    public void hide(Player player, HologramRenderer.Handle handle) {
        send(player, new WrapperPlayServerDestroyEntities(handle.entityId()));
    }

    private static List<EntityData<?>> metadata(HologramView view) {
        HologramStyle style = view.definition().style();
        List<EntityData<?>> data = new ArrayList<>();

        // Display metadata indices for the target Paper 26.1.2 protocol.
        data.add(new EntityData(8, EntityDataTypes.INT, 0));
        data.add(new EntityData(9, EntityDataTypes.INT, 0));
        data.add(new EntityData(10, EntityDataTypes.INT, 0));
        data.add(new EntityData(11, EntityDataTypes.VECTOR3F, new Vector3f()));
        data.add(new EntityData(12, EntityDataTypes.VECTOR3F,
                new Vector3f(style.scale(), style.scale(), style.scale())));
        data.add(new EntityData(13, EntityDataTypes.QUATERNION, new Quaternion4f(0, 0, 0, 1)));
        data.add(new EntityData(14, EntityDataTypes.QUATERNION, new Quaternion4f(0, 0, 0, 1)));
        data.add(new EntityData(15, EntityDataTypes.BYTE, billboard(style.billboard())));
        if (style.brightnessBlock() >= 0 && style.brightnessSky() >= 0) {
            int brightness = style.brightnessBlock() << 4 | style.brightnessSky() << 20;
            data.add(new EntityData(16, EntityDataTypes.INT, brightness));
        }
        data.add(new EntityData(17, EntityDataTypes.FLOAT, style.viewRange()));
        data.add(new EntityData(18, EntityDataTypes.FLOAT, 0.0f));
        data.add(new EntityData(19, EntityDataTypes.FLOAT, 0.0f));
        data.add(new EntityData(23, EntityDataTypes.ADV_COMPONENT, view.text()));
        data.add(new EntityData(24, EntityDataTypes.INT, style.lineWidth()));
        data.add(new EntityData(25, EntityDataTypes.INT, style.backgroundArgb()));
        data.add(new EntityData(26, EntityDataTypes.BYTE, style.textOpacity()));
        data.add(new EntityData(27, EntityDataTypes.BYTE, styleFlags(style)));
        return data;
    }

    private static byte billboard(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "fixed" -> 0;
            case "vertical" -> 1;
            case "horizontal" -> 2;
            default -> 3;
        };
    }

    private static byte styleFlags(HologramStyle style) {
        byte flags = 0;
        if (style.shadowed()) flags |= 1;
        if (style.seeThrough()) flags |= 2;
        if (style.defaultBackground()) flags |= 4;
        if (style.alignment().equalsIgnoreCase("left")) flags |= 8;
        if (style.alignment().equalsIgnoreCase("right")) flags |= 16;
        return flags;
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }
}
