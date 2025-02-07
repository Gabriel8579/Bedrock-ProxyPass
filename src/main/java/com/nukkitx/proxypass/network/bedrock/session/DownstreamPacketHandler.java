package com.nukkitx.proxypass.network.bedrock.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jwt.SignedJWT;
import com.nukkitx.nbt.NBTOutputStream;
import com.nukkitx.nbt.NbtList;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtType;
import com.nukkitx.nbt.util.stream.LittleEndianDataOutputStream;
import com.nukkitx.protocol.bedrock.BedrockClientSession;
import com.nukkitx.protocol.bedrock.data.inventory.ContainerId;
import com.nukkitx.protocol.bedrock.data.inventory.ItemData;
import com.nukkitx.protocol.bedrock.handler.BedrockPacketHandler;
import com.nukkitx.protocol.bedrock.packet.*;
import com.nukkitx.protocol.bedrock.util.EncryptionUtils;
import com.nukkitx.proxypass.ProxyPass;
import com.nukkitx.proxypass.network.bedrock.util.BlockPaletteUtils;
import com.nukkitx.proxypass.network.bedrock.util.RecipeUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.util.*;

@Log4j2
@RequiredArgsConstructor
public class DownstreamPacketHandler implements BedrockPacketHandler {
    private final BedrockClientSession session;
    private final ProxyPlayerSession player;
    private final ProxyPass proxy;
    private Int2ObjectMap<StartGamePacket.ItemEntry> itemEntries = new Int2ObjectOpenHashMap<>();

    public boolean handle(ServerToClientHandshakePacket packet) {
        try {
            SignedJWT saltJwt = SignedJWT.parse(packet.getJwt());
            URI x5u = saltJwt.getHeader().getX509CertURL();
            ECPublicKey serverKey = EncryptionUtils.generateKey(x5u.toASCIIString());
            SecretKey key = EncryptionUtils.getSecretKey(this.player.getProxyKeyPair().getPrivate(), serverKey,
                    Base64.getDecoder().decode(saltJwt.getJWTClaimsSet().getStringClaim("salt")));
            session.enableEncryption(key);
        } catch (ParseException | NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }

        ClientToServerHandshakePacket clientToServerHandshake = new ClientToServerHandshakePacket();
        session.sendPacketImmediately(clientToServerHandshake);
        return true;
    }

    public boolean handle(AvailableEntityIdentifiersPacket packet) {
        if (!proxy.getConfiguration().isExportData()) {
            return false;
        }
        proxy.saveNBT("entity_identifiers", packet.getIdentifiers());
        return false;
    }

    public boolean handle(BiomeDefinitionListPacket packet) {
        if (!proxy.getConfiguration().isExportData()) {
            return false;
        }
        proxy.saveNBT("biome_definitions", packet.getDefinitions());
        return false;
    }

    public boolean handle(StartGamePacket packet) {
        // TODO This block was commented on upstream ---- START
        /*if (!proxy.getConfiguration().isExportData()) {
            return false;
        }

        try {
            if (packet.getBlockPalette() != null) {
                Map<String, Integer> legacyBlocks = new HashMap<>();
                for (NbtMap entry : packet.getBlockPalette()) {
                    legacyBlocks.putIfAbsent(entry.getCompound("block").getString("name"), (int) entry.getShort("id"));
                }

                proxy.saveJson("legacy_block_ids.json", sortMap(legacyBlocks));
                List<NbtMap> palette = new ArrayList<>(packet.getBlockPalette());
                palette.sort(Comparator.comparingInt(value -> value.getShort("id")));
                proxy.saveNBT("runtime_block_states", new NbtList<>(NbtType.COMPOUND, palette));
                BlockPaletteUtils.convertToJson(proxy, palette);
            }
        } catch (Exception e) {
            log.fatal("Error while saving the legacy_block_ids.json and runtime_block_states.dat", e);
        }*/

        // TODO This block was commented on upstream ---- END

        List<DataEntry> itemData = new ArrayList<>();
        LinkedHashMap<String, Integer> legacyItems = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> legacyBlocks = new LinkedHashMap<>();

        for (StartGamePacket.ItemEntry entry : packet.getItemEntries()) {
            if (entry.getId() > 255) {
                legacyItems.putIfAbsent(entry.getIdentifier(), (int) entry.getId());
            } else {
                String id = entry.getIdentifier();
                if (id.contains(":item.")) {
                    id = id.replace(":item.", ":");
                }
                if (entry.getId() > 0) {
                    legacyBlocks.putIfAbsent(id, (int) entry.getId());
                } else {
                    legacyBlocks.putIfAbsent(id, 255 - entry.getId());
                }
            }

            if ("minecraft:shield".equals(entry.getIdentifier())) {
                log.info("Shield runtime ID {}", entry.getId());
                player.getUpstream().getHardcodedBlockingId().set(entry.getId());
                player.getDownstream().getHardcodedBlockingId().set(entry.getId());
            }

            itemData.add(new DataEntry(entry.getIdentifier(), entry.getId()));
            this.itemEntries.put(entry.getId(), entry);
            ProxyPass.legacyIdMap.put((int) entry.getId(), entry.getIdentifier());
        }

        for (int i = 0; i < itemData.size(); i++) {
            DataEntry dataEntry = itemData.get(i);
            itemData.set(i, new DataEntry(dataEntry.name, dataEntry.id, i));
        }
        itemData.sort(Comparator.comparing(o -> o.id));

        proxy.saveJson("legacy_block_ids.json", sortMap(legacyBlocks));
        proxy.saveJson("legacy_item_ids.json", sortMap(legacyItems));
        proxy.saveJson("runtime_item_states.json", itemData);

        return false;
    }

    @Override
    public boolean handle(CraftingDataPacket packet) {
        if (!proxy.getConfiguration().isExportData()) {
            return false;
        }

        RecipeUtils.writeRecipes(packet, this.proxy);
        return false;
    }

    @Override
    public boolean handle(DisconnectPacket packet) {
        this.session.disconnect();
        // Let the client see the reason too.
        return false;
    }

    private void dumpCreativeItems(List<ItemData> contents) {
        List<CreativeItemEntry> entries = new ArrayList<>();
        for (ItemData data : contents) {
            int runtimeId = data.getId();
            String id = this.itemEntries.get(runtimeId).getIdentifier();
            Integer damage = data.getDamage() == 0 ? null : (int) data.getDamage();
            Integer blockRuntimeId = data.getBlockRuntimeId() == 0 ? null : data.getBlockRuntimeId();

            NbtMap tag = data.getTag();
            String tagData = null;
            if (tag != null) {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try (NBTOutputStream stream = new NBTOutputStream(new LittleEndianDataOutputStream(byteArrayOutputStream))) {
                    stream.writeTag(tag);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                tagData = Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
            }
            entries.add(new CreativeItemEntry(id, damage, blockRuntimeId, tagData));
        }

        CreativeItems items = new CreativeItems(entries);

        proxy.saveJson("creativeitems.json", items);
    }

    @Override
    public boolean handle(CreativeContentPacket packet) {
        if (!proxy.getConfiguration().isExportData()) {
            return false;
        }
        dumpCreativeItems(Arrays.asList(packet.getContents()));

        // nukkix - start
        Path path = proxy.getDataDir().resolve("creative_contents.dat");
        ByteBuf buffer = ByteBufAllocator.DEFAULT.ioBuffer();
        try {
            ProxyPass.CODEC.tryEncode(buffer, packet, session);
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)){
                buffer.readBytes(out, buffer.readableBytes());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        } finally {
            buffer.release();
        }
        // nukkix - end
        return false;
    }

    // Pre 1.16 method of Creative Items
    @Override
    public boolean handle(InventoryContentPacket packet) {
        if (!proxy.getConfiguration().isExportData()) {
            return false;
        }
        if (packet.getContainerId() == ContainerId.CREATIVE) {
            dumpCreativeItems(packet.getContents());
        }
        return false;
    }

    private static Map<String, Integer> sortMap(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        Map<String, Integer> sortedMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : entries) {
            sortedMap.put(entry.getKey(), entry.getValue());
        }
        return sortedMap;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class CreativeItemEntry {
        private final String id;
        private final Integer damage;
        private final Integer blockRuntimeId;
        @JsonProperty("nbt_b64")
        private final String nbt;
    }

    @Value
    private static class CreativeItems {
        private final List<CreativeItemEntry> items;
    }

    @Value
    private static class RuntimeEntry {
        private static final Comparator<RuntimeEntry> COMPARATOR = Comparator.comparingInt(RuntimeEntry::getId)
                .thenComparingInt(RuntimeEntry::getData);

        private final String name;
        private final int id;
        private final int data;
    }

    @AllArgsConstructor
    @Value
    private static class DataEntry {
        private final String name;
        private final int id;
        private final int runtimeId;

        public DataEntry(String name, int id) {
            this.name = name;
            this.id = id;
            this.runtimeId = 0;
        }
    }
}
