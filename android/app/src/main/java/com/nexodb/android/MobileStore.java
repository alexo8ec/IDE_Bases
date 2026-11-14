package com.nexodb.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;

/** Passwords are AES-GCM encrypted with an application-only Android Keystore key. */
final class MobileStore {
    private final SharedPreferences prefs;private static final String ALIAS="nexodb.connection-secrets.v1";
    MobileStore(Context context){prefs=context.getSharedPreferences("nexodb",Context.MODE_PRIVATE);}
    String get(String key,String fallback){return prefs.getString(key,fallback);}void put(String key,String value){if(!prefs.edit().putString(key,value).commit())throw new IllegalStateException("No se pudo guardar la configuración.");}
    JSONObject settings(){try{return new JSONObject(get("settings","{\"language\":\"es\",\"theme\":\"system\"}"));}catch(JSONException e){return new JSONObject();}}
    JSONArray profiles(){try{return new JSONArray(get("profiles","[]"));}catch(JSONException e){throw new IllegalStateException("No se pudieron leer las conexiones guardadas.",e);}}
    JSONObject profile(String id)throws JSONException{JSONArray values=profiles();for(int i=0;i<values.length();i++)if(values.getJSONObject(i).optString("id").equals(id))return values.getJSONObject(i);throw new IllegalArgumentException("Selecciona una conexión guardada.");}
    JSONArray publicProfiles()throws JSONException{JSONArray result=profiles();for(int i=0;i<result.length();i++){JSONObject value=result.getJSONObject(i);boolean saved=!value.optString("secret").isBlank();value.remove("secret");value.put("hasPassword",saved);}return result;}
    void saveProfile(JSONObject input)throws Exception{
        JSONObject value=new JSONObject(input.toString());String id=value.optString("id");if(id.isBlank())id=UUID.randomUUID().toString();value.put("id",id);String password=value.optString("password");value.remove("password");value.remove("hasPassword");value.remove("secret");
        JSONArray values=profiles(),result=new JSONArray();JSONObject old=null;for(int i=0;i<values.length();i++){JSONObject item=values.getJSONObject(i);if(item.optString("id").equals(id))old=item;else result.put(item);}
        if(value.optBoolean("remember")){if(!password.isEmpty())value.put("secret",encrypt(password));else if(old!=null&&old.has("secret"))value.put("secret",old.getString("secret"));}
        result.put(value);put("profiles",result.toString());
    }
    void removeProfile(String id)throws JSONException{JSONArray values=profiles(),result=new JSONArray();for(int i=0;i<values.length();i++)if(!values.getJSONObject(i).optString("id").equals(id))result.put(values.getJSONObject(i));put("profiles",result.toString());}
    char[] password(JSONObject profile,String supplied)throws Exception{return !supplied.isEmpty()?supplied.toCharArray():profile.has("secret")?decrypt(profile.getString("secret")).toCharArray():new char[0];}
    private SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias(ALIAS)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}return (SecretKey)store.getKey(ALIAS,null);}
    private String encrypt(String text)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.getEncoder().encodeToString(cipher.getIV())+"."+Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));}
    private String decrypt(String text)throws Exception{String[] fields=text.split("\\.");if(fields.length!=2)throw new IllegalArgumentException("Contraseña guardada no válida.");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(fields[0])));return new String(cipher.doFinal(Base64.getDecoder().decode(fields[1])),StandardCharsets.UTF_8);}
}
