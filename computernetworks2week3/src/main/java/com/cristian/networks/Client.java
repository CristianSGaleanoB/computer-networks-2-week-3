package com.cristian.networks;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import com.google.gson.JsonSyntaxException;

public class Client {
    private static final String SERVER_IP   = "127.0.0.1";
    private static final int SERVER_PORT = 5000;

    public static void main(String[] args) {
        new Client().start();
    }

    public void start(){
        Scanner sc = new Scanner(System.in);

        System.out.println("Username: ");
        String input = sc.nextLine().trim();
        final String username = input.isEmpty() ? "anon" : input;

        int backoff = 1;
        while(true){        
            try(Socket socket = new Socket(SERVER_IP, SERVER_PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                
                System.out.println("Connected to the server.");

                // JOIN
                Message join = Message.now("join", username, "", "hello");
                sendLine(out, join.serializedWithMd5());


                // Receiver thread
                Thread receiver = new Thread(() -> receiveLoop(in, username), "receiver");
                receiver.setDaemon(true);
                receiver.start();

                System.out.println("Type messages. Use '@username message' for private messages. Press Ctrl+C to exit.");

                // User input loop
                while (true) {
                    String line = sc.nextLine();
                    if (line == null) break;
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("@")) {
                        int sp = line.indexOf(' ');
                        if (sp < 0) {
                            System.out.println("Format: @username message");
                            continue;
                        }
                        String to = line.substring(1, sp);
                        String text = line.substring(sp + 1);
                        Message pm = Message.now("pm", username, to, text);
                        sendLine(out, pm.serializedWithMd5());
                    } else {
                        Message msg = Message.now("msg", username, "", line);
                        sendLine(out, msg.serializedWithMd5());
                    
                    }
                }
                break; 

            } catch (SocketException se) {
                System.out.println("Connection lost: " + se.getMessage() + ". Retrying in " + backoff + "s...");
                sleep(backoff); 
                backoff = Math.min(backoff * 2, 30);
            } catch (IOException ioe) {
                System.out.println("Could not connect: " + ioe.getMessage() + ". Retrying in " + backoff + "s...");
                sleep(backoff);   
                backoff = Math.min(backoff * 2, 30);
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
                break;
            }
        }

        System.out.println("Client terminated.");
    }

    private void sendLine(BufferedWriter out, String line) throws IOException{
        synchronized (out) {
            out.write(line);
            out.write("\n");
            out.flush();
        }
    }

    private void receiveLoop(BufferedReader in, String username){
        try{
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;

                Message m;
                try {
                    m = Message.parseLine(line);
                } catch (JsonSyntaxException ex) {
                    System.out.println("[!] Malformed JSON");
                    continue;
                }

                if (!m.isMd5Valid()) {
                    System.out.println("[!] Corrupted message (MD5)");
                    continue;
                }

                switch (m.type) {
                    case "msg":
                        System.out.println("[" + m.from + "] " + m.text);
                        break;
                    case "pm":
                        if (username.equals(m.to)) {
                            System.out.println("[PM " + m.from + "→" + username + "] " + m.text);
                        }
                        break;
                    case "system":
                        System.out.println("[SYS] " + m.text);
                        break;
                    case "ack":
                        System.out.println("[OK] " + m.text);
                        break;
                    case "error":
                        System.out.println("[ERROR] " + m.text);
                        break;
                    default:
                        System.out.println("[?] " + line);
                }
            }
        } catch (IOException e) {
        }
    }

    private void sleep(int seconds){
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ignored) {
        }
    }

}
