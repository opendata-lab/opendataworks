package com.onedata.portal.service.permission;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PermissionCredentialCipherTest {
    @Test void authenticatedEncryptionUsesDifferentNoncesAndRejectsTampering() {
        PermissionCredentialCipher cipher=new PermissionCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String first=cipher.encrypt("secret"),second=cipher.encrypt("secret");
        assertNotEquals(first,second);assertEquals("secret",cipher.decrypt(first));
        byte[] bytes=Base64.getDecoder().decode(first.substring(3));bytes[bytes.length-1]^=1;
        assertThrows(IllegalStateException.class,()->cipher.decrypt("v1:"+Base64.getEncoder().encodeToString(bytes)));
    }
    @Test void missingOrWrongKeyNeverFallsBackToPlaintext() {
        assertThrows(IllegalStateException.class,()->new PermissionCredentialCipher("").encrypt("secret"));
        PermissionCredentialCipher a=new PermissionCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        byte[] other=new byte[32];other[0]=1;
        assertThrows(IllegalStateException.class,()->new PermissionCredentialCipher(Base64.getEncoder().encodeToString(other)).decrypt(a.encrypt("secret")));
    }
}
