package com.cristian.networks;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;


public class Message {
    public String type;
    public String from;
    public String to;
    public String text;
    public long ts;
    public String md5;

    public static final Gson GSON = new  GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    public Message(){}

    public Message(String type, String from, String to, String text, long ts){
        this.type = type;
        this.from = from;
        this.to = to;
        this.text = text;
        this.ts = ts;
    }

    private TreeMap<String, Object> toCanonicalMapWithoutMd5(){
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("from", (from == null ? "" : from));
        m.put("text", (text == null ? "" : text));
        m.put("to", (to == null ? "" : to));
        m.put("ts",ts);
        m.put("type", (type == null ? "" : type));
        return m;
    }
    public String canonicalJsonWithoutMd5(){
        return GSON.toJson(toCanonicalMapWithoutMd5());
    }

    public String computedMd5(){
        String canon = canonicalJsonWithoutMd5();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(canon.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for(byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 is not available", e);
        }
    }

    public String serializedWithMd5(){
        TreeMap<String, Object> m = toCanonicalMapWithoutMd5();
        this.md5 = computedMd5();
        m.put("md5", this.md5);
        return GSON.toJson(m);
    }

    public static Message parseLine(String line) throws JsonSyntaxException{
        Map<String, Object> raw = GSON.fromJson(line, MAP_TYPE);
        Message msg = new Message();
        msg.type = stringOf(raw.get("type"));
        msg.from = stringOf(raw.get("from"));
        msg.to = stringOf(raw.get("to"));
        msg.text = stringOf(raw.get("text"));
        msg.ts = longOf(raw.get("ts"));
        msg.md5 = stringOf(raw.get("md5"));
        return msg;
    }

    public boolean isMd5Valid(){
        if(md5 == null || md5.isEmpty()) return false;
        String expected = computedMd5();
        return expected.equalsIgnoreCase(md5);
    }

    private static String stringOf(Object o) {return (o == null) ? "" : String.valueOf(o);}
    private static Long longOf(Object o){
         if(o == null) return 0L;
         if(o instanceof  Number) return ((Number) o).longValue();
         try{return Long.valueOf(String.valueOf(o));}
         catch(NumberFormatException e){return  0L;} 
    }
    
    public static Message now(String type, String from, String to, String text){
        return new Message(type, from, to, text, Instant.now().getEpochSecond());
    }
}
