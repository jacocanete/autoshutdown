package com.jacocanete.autoshutdown;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class PlayerCountChecker {

    public static int getPlayerCount(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);

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

            if (packetId == 0x00) {
                String jsonResponse = readString(in);
                return parsePlayerCount(jsonResponse);
            }

        } catch (IOException e) {
            return -1; // Server offline or error
        }
        return -1;
    }

    public static int getPlayerCountFromProxy(RegisteredServer server) {
        if (server != null) {
            return server.getPlayersConnected().size();
        }
        return 0;
    }

    private static int parsePlayerCount(String jsonResponse) {
        try {
            // Simple JSON parsing to get player count
            // Look for "online":<number> in the players object
            int playersIndex = jsonResponse.indexOf("\"players\":");
            if (playersIndex != -1) {
                int onlineIndex = jsonResponse.indexOf("\"online\":", playersIndex);
                if (onlineIndex != -1) {
                    onlineIndex += 9; // Move past "online":
                    int commaIndex = jsonResponse.indexOf(",", onlineIndex);
                    int braceIndex = jsonResponse.indexOf("}", onlineIndex);

                    int endIndex = (commaIndex != -1 && commaIndex < braceIndex) ? commaIndex : braceIndex;
                    if (endIndex != -1) {
                        String countStr = jsonResponse.substring(onlineIndex, endIndex).trim();
                        return Integer.parseInt(countStr);
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to 0 if parsing fails
        }
        return 0;
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

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}