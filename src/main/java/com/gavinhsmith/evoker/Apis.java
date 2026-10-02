package com.gavinhsmith.evoker;

/** Base URLs of every upstream API, so tests can point them at a local server. */
record Apis(String mojang, String paper, String purpur, String fabric, String quilt, String neoforge, String spigot,
            String modrinth,
            String hangar) {
    static final Apis DEFAULT = new Apis(
            "https://piston-meta.mojang.com",
            "https://fill.papermc.io",
            "https://api.purpurmc.org",
            "https://meta.fabricmc.net",
            "https://meta.quiltmc.org",
            "https://maven.neoforged.net",
            "https://hub.spigotmc.org",
            "https://api.modrinth.com",
            "https://hangar.papermc.io");
}
