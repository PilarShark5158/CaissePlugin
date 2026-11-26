package fr.caisse;

import org.bukkit.inventory.ItemStack;

/** Un gain : soit un item, soit une commande console (%player% = nom du joueur). */
public class Reward {
    public ItemStack item;     // null si c'est une commande
    public String command;     // null si c'est un item
    public double weight;      // poids relatif (le % affiché = poids / total)

    public Reward(ItemStack item, String command, double weight) {
        this.item = item;
        this.command = command;
        this.weight = weight;
    }
}
