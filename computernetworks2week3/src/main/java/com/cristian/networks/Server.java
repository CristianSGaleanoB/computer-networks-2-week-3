package com.cristian.networks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.gson.JsonSyntaxException;


public class Server {
    private static final int PORT = 5000;

    private final ConcurrentMap<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private volatile boolean  running = true;

    public static void main(String[] args) {
        new Server().start();
    }

    public void start(){
        try(ServerSocket serverSocket = new ServerSocket(PORT)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server listening in 0.0.0.0 " + PORT);

            Thread console = new Thread(this::consoleLoop, "server-console");
            console.setDaemon(true);
            console.start();

            while(running){
                try{
                    Socket sock = serverSocket.accept();
                    sock.setKeepAlive(true);
                    ClientHandler handler = new ClientHandler(sock);
                    Thread t = new Thread(handler, "client-"  + sock.getRemoteSocketAddress());
                    t.setDaemon(true);
                    t.start();
                }catch(SocketException se){
                    if(running) System.err.println("Error accepted: " + se.getMessage());
                }catch(IOException io){
                    System.err.println("Error accepted " + io.getMessage());
                }
            }

            System.out.println("Server shutting down...");
        } catch (IOException e) {
            System.err.println("The server can't start: " + e.getMessage());
        }
    }

    
    private void consoleLoop() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("/list")) {
                    System.out.println("Conected: " + clients.keySet());
                } else if (line.equalsIgnoreCase("/quit")) {
                    broadcastSystem("Server shut down...");
                    shutdown();
                    break;
                } else if (line.startsWith("/pm ")) {
                    String[] parts = line.split(" ", 3);
                    if (parts.length < 3) {
                        System.out.println("Use: /pm <user> <message>");
                        continue;
                    }
                    sendPmFromServer(parts[1], parts[2]);
                } else {
                    broadcastSystem(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error console: " + e.getMessage());
        }
    }

    private void shutdown() {
        running = false;
        for (Map.Entry<String, ClientHandler> e : clients.entrySet()) {
            e.getValue().close();
        }
        clients.clear();
    }

    private void broadcastSystem(String text){
        Message m = Message.now("system","server","",text);
        String line = m.serializedWithMd5();
        clients.values().forEach(ch -> ch.sendLine(line));
    }

    private void sendPmFromServer(String toUser, String text){
        ClientHandler ch = clients.get(toUser);
        if (ch == null) {
            System.out.println("user not found: " + toUser);
            return;
        }

        Message m = Message.now("pm","server", toUser, text);
        ch.sendLine(m.serializedWithMd5());
    }

    private void broadcastFrom(String fromUser, String text){
        Message out = Message.now("msg", fromUser, "", text);
        String line = out.serializedWithMd5();
        clients.forEach((user, ch) -> {
            if(!user.equals(fromUser)) ch.sendLine(line);
        });
    }

    private void privateFromTo(String fromUser, String toUser, String text, ClientHandler sender){
        ClientHandler dest = clients.get(toUser);
        if (dest == null) {
            sender.sendError("User " + toUser + " is not connected");
            return;
        }

        Message pm = Message.now("pm", fromUser, toUser, text);
        dest.sendLine(pm.serializedWithMd5());
    

    }
    
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;
        private volatile boolean active = true;
        private String username;

        ClientHandler(Socket socket) throws IOException {
            this.socket = socket;
            this.in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public void run() {
            try {
                String first = in.readLine();
                if (first == null) { close(); return; }

                Message join;
                try {
                    join = Message.parseLine(first);
                } catch (JsonSyntaxException ex) {
                    sendError("JSON invalid in JOIN");
                    close();
                    return;
                }

                if (!"join".equalsIgnoreCase(join.type)) {
                    sendError("Expected 'join'");
                    close();
                    return;
                }
                if (!join.isMd5Valid()) {
                    sendError("MD5 invalid in JOIN");
                    close();
                    return;
                }

                String user = (join.from == null ? "" : join.from.trim());
                if (user.isEmpty()) {
                    sendError("User empty");
                    close();
                    return;
                }
                if (clients.putIfAbsent(user, this) != null) {
                    sendError("User already connected");
                    close();
                    return;
                }
                this.username = user;
                System.out.println("[+] " + username + " connected from " + socket.getRemoteSocketAddress());

                sendAck("Connected to server");
                broadcastSystem(username + " has join");

                String line;
                while (active && (line = in.readLine()) != null) {
                    if (line.isEmpty()) continue;

                    Message m;
                    try {
                        m = Message.parseLine(line);
                    } catch (JsonSyntaxException ex) {
                        sendError("JSON wrong form");
                        continue;
                    }

                    if (!m.isMd5Valid()) {
                        sendError("MD5 don't match; message discarded.");
                        continue;
                    }

                    switch (m.type) {
                        case "msg":
                            broadcastFrom(username, m.text);
                            break;
                        case "pm":
                            if (m.to == null || m.to.isEmpty()) {
                                sendError("PM require 'to'");
                            } else {
                                privateFromTo(username, m.to, m.text, this);
                            }
                            break;
                        default:
                            sendError("Type don't supported: " + m.type);
                    }
                }
            } catch (SocketException se) {
            } catch (IOException io) {
                System.err.println("IO with " + username + ": " + io.getMessage());
            } finally {
                if (username != null && clients.remove(username, this)) {
                    broadcastSystem(username + " leave");
                    System.out.println("[-] " + username + " disconnected");
                }
                close();
            }
        }

        void sendLine(String line) {
            synchronized (out) {
                try {
                    out.write(line);
                    out.write("\n");
                    out.flush();
                } catch (IOException e) {
                    active = false;
                    close();
                }
            }
        }

        void sendAck(String text) {
            Message m = Message.now("ack", "server", username, text);
            sendLine(m.serializedWithMd5());
        }

        void sendError(String text) {
            Message m = Message.now("error", "server", username, text);
            sendLine(m.serializedWithMd5());
        }

        void close() {
            active = false;
            try { in.close(); } catch (IOException ignore) {}
            try { out.close(); } catch (IOException ignore) {}
            try { socket.close(); } catch (IOException ignore) {}
        }
    }
}
