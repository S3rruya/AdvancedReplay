package me.jumper251.replay.arena;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.*;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static me.jumper251.replay.arena.ReplayFile.*;

/** Session-local virtual actors, sent only to their watcher. No real projectiles or NPCs. */
final class PacketActors {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_500_000_000);
    private final Player watcher;
    private final Map<UUID, Actor> actors = new HashMap<>();
    private record Actor(int id, UUID profile, State state) {}
    PacketActors(Player watcher) { this.watcher = watcher; }
    private void send(PacketWrapper<?> packet) { PacketEvents.getAPI().getPlayerManager().sendPacket(watcher, packet); }
    void state(UUID key, Point position, State state) {
        Actor actor = actors.get(key);
        if (actor == null) {
            actor = new Actor(NEXT_ID.getAndIncrement(), UUID.randomUUID(), null);
            if (state.type().equals("PLAYER")) {
                List<TextureProperty> textures = state.texture() == null ? List.of()
                        : List.of(new TextureProperty("textures", state.texture(), state.signature()));
                UserProfile profile = new UserProfile(actor.profile(), state.name(), textures);
                var info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile);
                info.setListed(false); info.setGameMode(GameMode.SURVIVAL);
                send(new WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED), info));
            }
            var type = EntityTypes.getByName("minecraft:" + state.type().toLowerCase(Locale.ROOT));
            if (type == null) throw new IllegalArgumentException("Entidade nao suportada: " + state.type());
            send(new WrapperPlayServerSpawnEntity(actor.id(), Optional.of(actor.profile()), type,
                    vector(position), position.pitch(), position.yaw(), position.yaw(), 0, Optional.empty()));
        }
        send(new WrapperPlayServerEntityPositionSync(actor.id(),
                new EntityPositionData(vector(position), new Vector3d(0, 0, 0), position.yaw(), position.pitch()), false));
        send(new WrapperPlayServerEntityHeadLook(actor.id(), position.yaw()));
        if (state.type().equals("PLAYER") && !state.equals(actor.state())) {
            EntityPose pose;
            try { pose = EntityPose.valueOf(state.pose()); } catch (IllegalArgumentException ignored) { pose = EntityPose.STANDING; }
            send(new WrapperPlayServerEntityMetadata(actor.id(), List.of(
                    new EntityData<>(0, EntityDataTypes.BYTE, (byte) state.flags()),
                    new EntityData<>(6, EntityDataTypes.ENTITY_POSE, pose),
                    new EntityData<>(17, EntityDataTypes.BYTE, (byte) 127))));
            EquipmentSlot[] slots = {EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.BOOTS,
                    EquipmentSlot.LEGGINGS, EquipmentSlot.CHEST_PLATE, EquipmentSlot.HELMET};
            List<Equipment> equipment = new ArrayList<>();
            for (int i = 0; i < Math.min(slots.length, state.equipment().size()); i++) {
                String encoded = state.equipment().get(i);
                ItemStack item = encoded.isEmpty() ? new ItemStack(org.bukkit.Material.AIR)
                        : ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
                equipment.add(new Equipment(slots[i], SpigotConversionUtil.fromBukkitItemStack(item)));
            }
            send(new WrapperPlayServerEntityEquipment(actor.id(), equipment));
        }
        actors.put(key, new Actor(actor.id(), actor.profile(), state));
    }
    void animate(UUID key, boolean hurt, boolean offhand) {
        Actor actor = actors.get(key);
        if (actor == null) return;
        if (hurt) send(new WrapperPlayServerHurtAnimation(actor.id(), 0));
        else send(new WrapperPlayServerEntityAnimation(actor.id(), offhand
                ? WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND
                : WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }
    void remove(UUID key) {
        Actor actor = actors.remove(key);
        if (actor == null) return;
        send(new WrapperPlayServerDestroyEntities(actor.id()));
        if (actor.state() != null && actor.state().type().equals("PLAYER"))
            send(new WrapperPlayServerPlayerInfoRemove(actor.profile()));
    }
    void clear() { List.copyOf(actors.keySet()).forEach(this::remove); }
    private static Vector3d vector(Point point) { return new Vector3d(point.x(), point.y(), point.z()); }
}
