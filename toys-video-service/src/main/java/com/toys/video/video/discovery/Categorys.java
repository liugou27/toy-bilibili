package com.toys.video.video.discovery;

import java.util.List;

/**
 * 视频分区(有序):投稿/编辑可选设置与首页分区筛选。
 * key 为存储与接口标识,name 为中文展示名。
 */
public final class Categorys {

    private static final List<Category> ALL = List.of(
            new Category("anime", "动画"),
            new Category("game", "游戏"),
            new Category("tech", "科技"),
            new Category("life", "生活"),
            new Category("music", "音乐"),
            new Category("film", "影视"),
            new Category("knowledge", "知识"),
            new Category("food", "美食"));

    private Categorys() {
    }

    public static List<Category> all() {
        return ALL;
    }

    /** 非空且命中已知分区 key 才合法。 */
    public static boolean isValid(String key) {
        return key != null && !key.isBlank() && ALL.stream().anyMatch(c -> c.key().equals(key));
    }

    /** 合法 key 的展示名;未知 key 返回 null。 */
    public static String nameOf(String key) {
        return ALL.stream().filter(c -> c.key().equals(key)).findFirst().map(Category::name).orElse(null);
    }

    public record Category(String key, String name) {
    }
}
