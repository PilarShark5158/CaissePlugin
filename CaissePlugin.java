package fr.caisse;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CaissePlugin extends JavaPlugin {

    public final Map<String, Crate> crates = new LinkedHashMap<>();
    public NamespacedKey keyTag;    // tag PDC porté par les clefs (valeur = id de la caisse)
    public NamespacedKey blockTag;  // tag PDC porté par l'item "bloc de caisse" (valeur = id)
    private File file;
    private Gui gui;

    @Override
    public void onEnable() {
        keyTag = new NamespacedKey(this, "cle");
        blockTag = new NamespacedKey(this, "bloc");
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "crates.yml");
        load();
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(gui, this);
    }

    @Override
    public void onDisable() {
        save();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("caisse")) {
            gui.openList(p);
        } else if (cmd.getName().equalsIgnoreCase("caisseadmin")) {
            if (!p.hasPermission("caisse.admin")) {
                p.sendMessage(Gui.t("&cVous n'avez pas la permission."));
                return true;
            }
            gui.openMain(p);
        }
        return true;
    }

    // ---------- Blocs ----------

    public static String bk(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public Crate crateAt(Block b) {
        String k = bk(b);
        for (Crate c : crates.values()) if (c.blocks.contains(k)) return c;
        return null;
    }

    public ItemStack blockItem(Crate c) {
        ItemStack i = new ItemStack(c.block);
        ItemMeta m = i.getItemMeta();
        m.displayName(Gui.t(c.name));
        m.lore(List.of(Gui.t("&7Caisse : &f" + c.id), Gui.t("&7Posez-la pour créer un point d'ouverture.")));
        m.getPersistentDataContainer().set(blockTag, PersistentDataType.STRING, c.id);
        i.setItemMeta(m);
        return i;
    }

    // ---------- Clefs ----------

    /** Copie l'objet en main, quantité 1, taguée avec l'id de la caisse. */
    public ItemStack tagKey(ItemStack hand, Crate c) {
        ItemStack i = hand.clone();
        i.setAmount(1);
        ItemMeta m = i.getItemMeta();
        m.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, c.id);
        i.setItemMeta(m);
        return i;
    }

    public ItemStack makeKey(Crate c, int n) {
        ItemStack i = c.key.clone();
        i.setAmount(n);
        return i;
    }

    public boolean isKey(ItemStack i, Crate c) {
        if (i == null || !i.hasItemMeta()) return false;
        return c.id.equals(i.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING));
    }

    public int countKeys(Player p, Crate c) {
        if (c.key == null) return 0;
        int n = 0;
        for (ItemStack i : p.getInventory().getContents()) if (isKey(i, c)) n += i.getAmount();
        return n;
    }

    public boolean takeKey(Player p, Crate c) {
        for (int s = 0; s < p.getInventory().getSize(); s++) {
            ItemStack i = p.getInventory().getItem(s);
            if (isKey(i, c)) {
                if (i.getAmount() <= 1) p.getInventory().setItem(s, null);
                else i.setAmount(i.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // ---------- Sauvegarde ----------

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Crate c : crates.values()) {
            String p = "crates." + c.id + ".";
            y.set(p + "name", c.name);
            y.set(p + "block", c.block.name());
            y.set(p + "key", c.key);
            y.set(p + "blocks", new ArrayList<>(c.blocks));
            int i = 0;
            for (Reward r : c.rewards) {
                String q = p + "rewards." + (i++) + ".";
                y.set(q + "item", r.item);
                y.set(q + "command", r.command);
                y.set(q + "weight", r.weight);
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder crates.yml : " + e.getMessage());
        }
    }

    private void load() {
        crates.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("crates");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            String p = "crates." + id + ".";
            Crate c = new Crate(id, y.getString(p + "name", "&6" + id));
            Material m = Material.matchMaterial(y.getString(p + "block", "CHEST"));
            c.block = m != null ? m : Material.CHEST;
            c.key = y.getItemStack(p + "key");
            c.blocks.addAll(y.getStringList(p + "blocks"));
            ConfigurationSection rs = y.getConfigurationSection(p + "rewards");
            if (rs != null) {
                for (String k : rs.getKeys(false)) {
                    String q = p + "rewards." + k + ".";
                    c.rewards.add(new Reward(y.getItemStack(q + "item"), y.getString(q + "command"), y.getDouble(q + "weight", 1)));
                }
            }
            crates.put(id, c);
        }
    }
}
