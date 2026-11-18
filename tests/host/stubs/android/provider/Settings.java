package android.provider;
import android.content.ContentResolver;
public class Settings { public static class Secure { public static String value; public static String getString(ContentResolver c,String k){return value;} } }
