package controllers;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class Quake3StatusSchedulerTest {
    // ETQW 1.5 speaks protocol 10.21 (verified): no rate field, with clan tags.
    private static final int PROTOCOL_1_5 = 0xa0015;

    /** An ET:QW infoResponse laid out as qstat's doom3.c parses it (version 5). */
    private static byte[] etqwResponse(int protocol, boolean withRate, String... players) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xFF);
        string(out, "infoResponse");
        int32(out, 7);          // task id
        int32(out, 0);          // challenge
        int32(out, protocol);
        int32(out, 0);          // size
        string(out, "si_name");
        string(out, "ET:QW | DoomWarriors | 6v6");
        string(out, "si_maxPlayers");
        string(out, "12");
        string(out, "si_map");
        string(out, "maps/area22.entities");
        string(out, "");
        string(out, "");
        int id = 0;
        for (String name : players) {
            out.write(id++);
            out.write(50);      // ping, 2 bytes LE
            out.write(0);
            if (withRate) int32(out, 25000);
            string(out, name);
            out.write(0);       // clan tag position
            string(out, "DWC");
            out.write(name.startsWith("Bot") ? 1 : 0);
        }
        out.write(32);          // end of player list
        int32(out, 0x1);        // osmask
        out.write(0);           // ranked
        int32(out, 600000);     // time left
        out.write(1);           // game state
        out.write(0);           // server type: regular
        out.write(0);           // interested clients
        return out.toByteArray();
    }

    private static void string(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
        out.write(0);
    }

    private static void int32(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static Quake3StatusScheduler.Info parse(byte[] packet) {
        return Quake3StatusScheduler.parseEtqwInfoResponse(packet, packet.length);
    }

    @Test
    public void readsARealEtqw15Response() throws IOException {
        // Captured 2026-11-19 from the etqw-server container (ETQW 1.5.12663,
        // protocol 10.21) right after start: campaign_northamerica on valley,
        // 12 bots filling the 6v6, answer to exactly the packet this class sends.
        byte[] packet = Files.readAllBytes(Path.of("test/resources/etqw-1.5-inforesponse.bin"));
        Quake3StatusScheduler.Info info = parse(packet);
        assertEquals(Integer.valueOf(12), info.players());
        assertEquals(Integer.valueOf(12), info.maxPlayers());
        assertEquals("valley", info.map());
    }

    @Test
    public void readsPlayersMaxPlayersAndMapOfAnEtqwServer() {
        Quake3StatusScheduler.Info info = parse(etqwResponse(PROTOCOL_1_5, false, "n4cer", "BotGDF1", "BotStrogg1"));
        assertEquals(Integer.valueOf(3), info.players());
        assertEquals(Integer.valueOf(12), info.maxPlayers());
        assertEquals("area22", info.map());
    }

    @Test
    public void emptyServerHasZeroPlayers() {
        assertEquals(Integer.valueOf(0), parse(etqwResponse(PROTOCOL_1_5, false)).players());
    }

    @Test
    public void oldProtocolsCarryARateField() {
        assertEquals(Integer.valueOf(2), parse(etqwResponse(0xa0013, true, "a", "b")).players());
    }

    @Test
    public void bitPackedProtocolsAreOnlineWithoutAPlayerCount() {
        Quake3StatusScheduler.Info info = parse(etqwResponse(0xd000a, false, "a"));
        assertNull(info.players());
        assertEquals(Integer.valueOf(12), info.maxPlayers());
    }

    @Test
    public void truncatedPlayerListKeepsTheServerInfo() {
        byte[] full = etqwResponse(PROTOCOL_1_5, false, "n4cer");
        Quake3StatusScheduler.Info info = Quake3StatusScheduler.parseEtqwInfoResponse(full, full.length - 18);
        assertNull(info.players());
        assertEquals("area22", info.map());
    }

    @Test
    public void rejectsPacketsThatAreNoInfoResponse() {
        byte[] quake3 = "ÿÿÿÿinfoResponse\n\\clients\\1".getBytes(StandardCharsets.ISO_8859_1);
        assertNull(parse(quake3));
        assertNull(parse(new byte[]{(byte) 0xFF, (byte) 0xFF}));
    }
}
