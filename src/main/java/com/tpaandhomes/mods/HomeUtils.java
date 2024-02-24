package com.tpaandhomes.mods;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.network.ServerPlayerEntity;

import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class HomeUtils {

    private static final String GLOBAL_WARPS_KEY = "$$globalWarps$$";

    private final Path path;

    private final Logger logger;

    private Map<String, List<HomeLocation>> homes;

    private Gson gson;

    public HomeUtils(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.init();
    }

    public void init() {
        try {
            final var json = Files.readString(this.path);
            this.homes = this.gson.fromJson(json, new TypeToken<Map<String, List<HomeLocation>>>() {}.getType());
        } catch (IOException e) {
            this.logger.fatal("Failed to load homes file!");
            this.homes = new HashMap<>();
        }
    }

    public void save() {
        try {
            final var json = this.gson.toJson(homes);
            Files.writeString(this.path, json);
        } catch (IOException e) {
            this.logger.fatal("Failed to save data to homes file!");
        }
    }

    private String getHomeKey(final ServerPlayerEntity player, final boolean isGlobal) {
        return isGlobal ? GLOBAL_WARPS_KEY : player.getEntityName();
    }

    public List<HomeLocation> getHomes(final ServerPlayerEntity player, final boolean isGlobal) {
        final String key = this.getHomeKey(player, isGlobal);

        if (!this.homes.containsKey(key)) {
            this.homes.put(key, new ArrayList<>());
        }
        return this.homes.get(key);
    }

    public void setNewHome(final ServerPlayerEntity player, final String name, final boolean isGlobal) {
        final var homes = this.getHomes(player, isGlobal);

        final HomeLocation newHome = new HomeLocation();
        newHome.name = name.toLowerCase();
        newHome.dimension = player.getServerWorld().getRegistryKey().getValue().getPath();
        newHome.x = player.getX();
        newHome.y = player.getY();
        newHome.z = player.getZ();
        newHome.yaw = player.getYaw();
        newHome.pitch = player.getPitch();

        homes.add(newHome);
    }

    public static class HomeLocation implements Serializable {

        public String name;

        public String dimension;

        public double x, y, z;

        public float yaw, pitch;

    }

}
