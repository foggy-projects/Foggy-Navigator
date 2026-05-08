package com.foggy.navigator.auth.util;

public class GenerateJwt {
    public static void main(String[] args) throws Exception {
        JwtUtil jwtUtil = new JwtUtil();
        java.lang.reflect.Field f = JwtUtil.class.getDeclaredField("secret");
        f.setAccessible(true);
        f.set(jwtUtil, "foggy-navigator-jwt-secret-key-change-in-production");
        java.lang.reflect.Field f2 = JwtUtil.class.getDeclaredField("expiration");
        f2.setAccessible(true);
        f2.set(jwtUtil, 86400L * 30);
        String token = jwtUtil.generateToken("679a91c2-c60c-42ce-ac75-933ed67c0ad7", "root", "88800", "SUPER_ADMIN");
        System.out.println("NEW_TOKEN=" + token);
    }
}
