package com.jacocanete.autoshutdown;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ServerPinger {

    public static boolean isServerOnline(String host, int port) {
        return isServerOnline(host, port, 5000);
    }

    public static boolean isServerOnline(String host, int port, int timeoutMs) {
        // First do a simple TCP connection test
        if (!canConnect(host, port, timeoutMs)) {
            return false;
        }

        // Then try Minecraft protocol ping
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs); // Set read timeout too

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Send handshake packet
            writeVarInt(out, 0x00); // Packet ID
            writeVarInt(out, 47); // Protocol version (1.8.x)
            writeString(out, host);
            out.writeShort(port);
            writeVarInt(out, 1); // Next state (status)

            // Send status request
            writeVarInt(out, 0x00); // Packet ID

            // Read response
            int length = readVarInt(in);
            int packetId = readVarInt(in);

            return packetId == 0x00;

        } catch (IOException e) {
            // If MC protocol fails but TCP works, assume server is online but not MC
            return true;
        }
    }

    public static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0x80) != 0) {
            out.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) break;

            position += 7;

            if (position >= 32) {
                throw new RuntimeException("VarInt is too big");
            }
        }

        return value;
    }

    private static void writeString(DataOutputStream out, String string) throws IOException {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
}