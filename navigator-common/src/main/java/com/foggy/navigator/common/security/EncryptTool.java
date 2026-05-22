package com.foggy.navigator.common.security;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

public class EncryptTool {
    public static void main(String[] args) {
        String key = "default-dev-key-change-in-prod";
        String salt = "abcdef0123456789";
        if (args.length >= 2) {
            key = args[0];
            salt = args[1];
        }
        TextEncryptor encryptor = Encryptors.text(key, salt);
        String encrypted = encryptor.encrypt("sk-ali-only");
        System.out.println("RESULT_START:" + encrypted + ":RESULT_END");
    }
}
