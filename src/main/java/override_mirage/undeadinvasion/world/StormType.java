package override_mirage.undeadinvasion.world;

/**
 * 特殊降雨类型；同一时刻最多只有一种生效（互斥）。
 *
 * <p>用 ordinal 作为网络传输的稳定编号，新增类型请追加在末尾，不要调整已有顺序。
 *
 * <p>每种雨自带三套配色：雨柱/水花用的 0xRRGGBB 与归一化 RGB、以及雾霾用的
 * 归一化 RGB（雾色通常比雨色更暗一点，避免整片天空发亮）。集中在这里定义，
 * 客户端的染色、雾、粒子都从这里取值，保证同一种雨各处色调一致。
 */
public enum StormType {
    /** 无特殊降雨，原版普通雨/晴。 */
    NONE(0x000000, 0.0F, 0.0F, 0.0F, 0x000000, 0.0F, 0.0F, 0.0F),
    /** 酸雨：亮绿色，无来源环境伤害；酸雾用压暗的苔绿。 */
    ACID(0x64FF40, 100.0F / 255.0F, 1.0F, 64.0F / 255.0F,
            0x3A8C28, 58.0F / 255.0F, 140.0F / 255.0F, 40.0F / 255.0F),
    /**
     * 硫磺雨：金黄 {@code #FFB800}（R255 G184 B0），点燃暴露在雨中的生物。
     *
     * <p>刻意把 G 压到明显低于 R、并把 B 归零：早期用的 {@code #FFD429} 因 G 高达 212，
     * 半透明雨柱叠在蓝灰天空上会明显泛绿，看起来像酸雨。
     */
    SULFUR(0xFFB800, 1.0F, 184.0F / 255.0F, 0.0F,
            0xE0A000, 224.0F / 255.0F, 160.0F / 255.0F, 0.0F);

    private final int color;
    private final float red, green, blue;
    private final int fogColor;
    private final float fogRed, fogGreen, fogBlue;

    StormType(int color, float red, float green, float blue,
              int fogColor, float fogRed, float fogGreen, float fogBlue) {
        this.color = color;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.fogColor = fogColor;
        this.fogRed = fogRed;
        this.fogGreen = fogGreen;
        this.fogBlue = fogBlue;
    }

    /** 雨柱染色用的 0xRRGGBB。 */
    public int color() { return this.color; }

    /** 水花粒子用的归一化 RGB。 */
    public float red() { return this.red; }
    public float green() { return this.green; }
    public float blue() { return this.blue; }

    /** 雾霾用的压暗 0xRRGGBB。 */
    public int fogColor() { return this.fogColor; }

    /** 雾霾用的归一化 RGB。 */
    public float fogRed() { return this.fogRed; }
    public float fogGreen() { return this.fogGreen; }
    public float fogBlue() { return this.fogBlue; }

    public boolean isActive() { return this != NONE; }

    public static StormType fromId(int id) {
        StormType[] values = values();
        return id >= 0 && id < values.length ? values[id] : NONE;
    }

    public int id() { return this.ordinal(); }
}
