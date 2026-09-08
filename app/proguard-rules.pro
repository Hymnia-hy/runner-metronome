# 枚举通过 Intent 传来的字符串做 valueOf 反射解析，需保留成员
-keepclassmembers enum com.example.runmetronome.Tone {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
