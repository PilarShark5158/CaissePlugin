package fr.caisse;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Crate {
    public final String id;
    public String name;
    public Material block = Material.CHEST;          // bloc de référence
    public ItemStack key;                            // objet clef (tagué), null si non défini
    public final List<Reward> rewards = new ArrayList<>();
    public final Set<String> blocks = new HashSet<>(); // "monde;x;y;z" des caisses posées

    public Crate(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public double total() {
        double t = 0;
        for (Reward r : rewards) t += r.weight;
        return t;
    }

    /** Tirage pondéré. Renvoie null si aucun gain possible. */
    public Reward roll() {
        double t = total();
        if (t <= 0) return null;
        double x = ThreadLocalRandom.current().nextDouble() * t;
        for (Reward r : rewards) {
            x -= r.weight;
            if (x < 0 && r.weight > 0) return r;
        }
        return rewards.get(rewards.size() - 1);
    }
}
