package fr.caisse;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class Gui implements Listener {

    enum Type { LIST, PREVIEW, ADMIN_MAIN, ADMIN_CRATES, ADMIN_KEYS, EDIT, REWARDS, ANIM }

    static class H implements InventoryHolder {
        final Type type;
        final Crate crate;
        boolean fromList, delivered;
        Reward winner;
        Player player;
        BukkitTask task;
        Inventory inv;

        H(Type type, Crate crate) {
            this.type = type;
            this.crate = crate;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final CaissePlugin pl;
    private final Map<UUID, Consumer<String>> pending = new HashMap<>();
    private static final List<Material> BLOCKS = new ArrayList<>();

    static {
        BLOCKS.addAll(List.of(Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL));
        for (Material m : Material.values()) {
            if (m.name().endsWith("SHULKER_BOX") && !m.name().startsWith("LEGACY") && m.isItem()) BLOCKS.add(m);
        }
    }

    public Gui(CaissePlugin pl) {
        this.pl = pl;
    }

    // ---------- Helpers ----------

    public static Component t(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    static ItemStack it(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta mt = i.getItemMeta();
        mt.displayName(t(name));
        if (lore.length > 0) mt.lore(Arrays.stream(lore).map(Gui::t).toList());
        i.setItemMeta(mt);
        return i;
    }

    static ItemStack withLore(ItemStack base, String... extra) {
        ItemStack i = base.clone();
        ItemMeta m = i.getItemMeta();
        List<Component> l = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
        for (String s : extra) l.add(t(s));
        m.lore(l);
        i.setItemMeta(m);
        return i;
    }

    private Inventory mk(H h, int size, String title) {
        Inventory inv = Bukkit.createInventory(h, size, t(title));
        h.inv = inv;
        return inv;
    }

    private ItemStack icon(Reward r) {
        if (r.item != null) return r.item.clone();
        return it(Material.COMMAND_BLOCK, "&eCommande", "&7/" + r.command);
    }

    private String pct(Reward r, Crate c) {
        double tot = c.total();
        return String.format(Locale.US, "%.2f", tot <= 0 ? 0 : r.weight / tot * 100);
    }

    private void give(Player p, ItemStack i) {
        p.getInventory().addItem(i).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
    }

    private void ask(Player p, String prompt, Consumer<String> cb) {
        pending.put(p.getUniqueId(), cb);
        Bukkit.getScheduler().runTask(pl, () -> p.closeInventory());
        p.sendMessage(t(prompt + " &7(tapez &cannuler&7 pour annuler)"));
    }

    private boolean admin(Player p) {
        if (p.hasPermission("caisse.admin")) return true;
        p.closeInventory();
        return false;
    }

    // ---------- /caisse ----------

    public void openList(Player p) {
        List<Crate> list = new ArrayList<>(pl.crates.values());
        if (list.isEmpty()) {
            p.sendMessage(t("&cAucune caisse n'est configurée."));
            return;
        }
        H h = new H(Type.LIST, null);
        Inventory inv = mk(h, Math.min(54, Math.max(9, (list.size() + 8) / 9 * 9)), "&8Caisses");
        for (int i = 0; i < list.size() && i < 54; i++) {
            Crate c = list.get(i);
            inv.setItem(i, it(c.block, c.name,
                    "&7Gains : &e" + c.rewards.size(),
                    "&7Clefs possédées : &e" + pl.countKeys(p, c),
                    "",
                    "&eCliquez pour voir / ouvrir"));
        }
        p.openInventory(inv);
    }

    public void openPreview(Player p, Crate c, boolean fromList) {
        H h = new H(Type.PREVIEW, c);
        h.fromList = fromList;
        Inventory inv = mk(h, 54, c.name + " &8- Gains");
        for (int i = 0; i < c.rewards.size() && i < 45; i++) {
            Reward r = c.rewards.get(i);
            inv.setItem(i, withLore(icon(r), "", "&7Chance : &e" + pct(r, c) + "%"));
        }
        int keys = pl.countKeys(p, c);
        if (keys > 0 && c.total() > 0) {
            inv.setItem(49, it(Material.LIME_CONCRETE, "&aOuvrir la caisse", "&7Consomme &e1 clef"));
        } else if (keys > 0) {
            inv.setItem(49, it(Material.RED_CONCRETE, "&cCaisse vide", "&7Aucun gain configuré."));
        } else {
            inv.setItem(49, it(Material.RED_CONCRETE, "&cClef requise", "&7Vous n'avez pas la clef."));
        }
        if (c.key != null) inv.setItem(53, withLore(c.key, "", "&7Clef requise", "&7Vous en avez : &e" + keys));
        if (fromList) inv.setItem(45, it(Material.ARROW, "&7Retour"));
        p.openInventory(inv);
    }

    private void tryOpen(Player p, Crate c) {
        if (c.total() <= 0) {
            p.sendMessage(t("&cCette caisse est vide."));
            return;
        }
        if (!pl.takeKey(p, c)) {
            p.sendMessage(t("&cVous n'avez pas la clef de cette caisse."));
            p.closeInventory();
            return;
        }
        startAnim(p, c, c.roll());
    }

    // ---------- Animation ----------

    private ItemStack rndIcon(Crate c) {
        return icon(c.rewards.get(ThreadLocalRandom.current().nextInt(c.rewards.size())));
    }

    private void startAnim(Player p, Crate c, Reward w) {
        H h = new H(Type.ANIM, c);
        h.winner = w;
        h.player = p;
        Inventory inv = mk(h, 27, c.name);
        ItemStack pane = it(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        ItemStack ptr = it(Material.YELLOW_STAINED_GLASS_PANE, " ");
        inv.setItem(4, ptr);
        inv.setItem(22, ptr);
        for (int i = 9; i < 18; i++) inv.setItem(i, rndIcon(c));
        p.openInventory(inv);
        h.task = new BukkitRunnable() {
            int step = 0;
            final int total = 26;

            @Override
            public void run() {
                step++;
                for (int i = 9; i < 17; i++) inv.setItem(i, inv.getItem(i + 1));
                // l'item qui entre en 17 atteint le slot 13 après 4 décalages
                inv.setItem(17, step == total - 4 ? icon(w) : rndIcon(c));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                if (step >= total) {
                    cancel();
                    finish(h);
                }
            }
        }.runTaskTimer(pl, 0L, 3L);
    }

    private void finish(H h) {
        if (h.delivered) return;
        h.delivered = true;
        deliver(h.player, h.crate, h.winner);
        h.player.playSound(h.player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (h.player.getOpenInventory().getTopInventory().getHolder() == h) h.player.closeInventory();
        }, 40L);
    }

    private void deliver(Player p, Crate c, Reward r) {
        if (r == null) return;
        if (r.item != null) {
            give(p, r.item.clone());
            p.sendMessage(t("&aVous avez gagné ").append(r.item.displayName()).append(t(" &ax" + r.item.getAmount())));
        } else if (r.command != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), r.command.replace("%player%", p.getName()));
            p.sendMessage(t("&aVous avez gagné une récompense de la caisse " + c.name + "&a !"));
        }
    }

    // ---------- /caisseadmin ----------

    public void openMain(Player p) {
        H h = new H(Type.ADMIN_MAIN, null);
        Inventory inv = mk(h, 27, "&8Administration des caisses");
        inv.setItem(11, it(Material.CHEST, "&6Caisse", "&7Créer et configurer les caisses"));
        inv.setItem(15, it(Material.TRIPWIRE_HOOK, "&6Clefs", "&7Voir les clefs", "&eClic droit : recevoir la clef"));
        p.openInventory(inv);
    }

    private void openCrates(Player p) {
        H h = new H(Type.ADMIN_CRATES, null);
        Inventory inv = mk(h, 54, "&8Caisses - Configuration");
        int i = 0;
        for (Crate c : pl.crates.values()) {
            if (i >= 45) break;
            inv.setItem(i++, it(c.block, c.name,
                    "&7ID : &f" + c.id,
                    "&7Gains : &e" + c.rewards.size(),
                    "&7Blocs posés : &e" + c.blocks.size(),
                    "",
                    "&eClic gauche : configurer",
                    "&eClic droit : recevoir le bloc à poser"));
        }
        inv.setItem(45, it(Material.ARROW, "&7Retour"));
        inv.setItem(49, it(Material.EMERALD, "&aCréer une caisse"));
        p.openInventory(inv);
    }

    private void openKeys(Player p) {
        H h = new H(Type.ADMIN_KEYS, null);
        Inventory inv = mk(h, 54, "&8Clefs");
        int i = 0;
        for (Crate c : pl.crates.values()) {
            if (i >= 45) break;
            if (c.key == null) {
                inv.setItem(i, it(Material.BARRIER, c.name, "&cAucune clef définie", "&7Définissez-la dans la config de la caisse."));
            } else {
                inv.setItem(i, withLore(c.key, "", "&7Caisse : " + c.name,
                        "&eClic droit : recevoir 1 clef", "&eMaj + clic droit : recevoir 16 clefs"));
            }
            i++;
        }
        inv.setItem(45, it(Material.ARROW, "&7Retour"));
        p.openInventory(inv);
    }

    private void openEdit(Player p, Crate c) {
        H h = new H(Type.EDIT, c);
        Inventory inv = mk(h, 27, "&8Config : " + c.name);
        inv.setItem(4, it(c.block, c.name, "&7Clic : recevoir le bloc à poser"));
        inv.setItem(10, it(Material.NAME_TAG, "&eRenommer", "&7Nom actuel : " + c.name));
        inv.setItem(12, it(c.block, "&eBloc de référence", "&7Actuel : &f" + c.block.name(),
                "&7Clic : changer (coffre, baril, shulker...)",
                "&8Ne change pas les blocs déjà posés."));
        if (c.key == null) {
            inv.setItem(14, it(Material.BARRIER, "&cClef non définie",
                    "&eClic gauche : utiliser l'objet en main", "&7(tenez l'objet voulu en main)"));
        } else {
            inv.setItem(14, withLore(c.key, "", "&eClic gauche : remplacer par l'objet en main",
                    "&eClic droit : recevoir 1 clef"));
        }
        inv.setItem(16, it(Material.ENDER_CHEST, "&6Gains", "&7" + c.rewards.size() + " gain(s)", "&eClic : configurer items, commandes, taux"));
        inv.setItem(18, it(Material.ARROW, "&7Retour"));
        inv.setItem(22, it(Material.TNT, "&cSupprimer la caisse", "&7Maj + clic pour confirmer"));
        p.openInventory(inv);
    }

    private void openRewards(Player p, Crate c) {
        H h = new H(Type.REWARDS, c);
        Inventory inv = mk(h, 54, "&8Gains : " + c.name);
        fillRewards(inv, c);
        p.openInventory(inv);
    }

    private void fillRewards(Inventory inv, Crate c) {
        inv.clear();
        for (int i = 0; i < c.rewards.size() && i < 45; i++) {
            Reward r = c.rewards.get(i);
            inv.setItem(i, withLore(icon(r), "",
                    "&7Poids : &e" + r.weight + " &7(&e" + pct(r, c) + "%&7)",
                    "&eClic gauche : +1  &7| &eClic droit : -1",
                    "&eMaj + clic : ±10",
                    "&eMolette : saisir la valeur",
                    "&cQ : supprimer"));
        }
        inv.setItem(45, it(Material.ARROW, "&7Retour"));
        inv.setItem(49, it(Material.PAPER, "&aAjouter un item",
                "&7Cliquez sur un objet de &fvotre inventaire", "&7pour l'ajouter comme gain (poids 10)."));
        inv.setItem(53, it(Material.COMMAND_BLOCK, "&aAjouter une commande",
                "&7Commande console, %player% = le joueur"));
    }

    // ---------- Clics ----------

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof H) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof H h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        boolean top = e.getClickedInventory() == e.getInventory();
        int s = e.getSlot();

        // Ajout d'un item de l'inventaire du joueur comme gain
        if (h.type == Type.REWARDS && !top) {
            if (!admin(p)) return;
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType().isAir()) return;
            if (h.crate.rewards.size() >= 45) {
                p.sendMessage(t("&cMaximum 45 gains par caisse."));
                return;
            }
            h.crate.rewards.add(new Reward(cur.clone(), null, 10));
            pl.save();
            fillRewards(e.getInventory(), h.crate);
            return;
        }
        if (!top) return;

        switch (h.type) {
            case LIST -> {
                List<Crate> list = new ArrayList<>(pl.crates.values());
                if (s >= 0 && s < list.size()) openPreview(p, list.get(s), true);
            }
            case PREVIEW -> {
                if (s == 49 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.LIME_CONCRETE) {
                    tryOpen(p, h.crate);
                } else if (s == 45 && h.fromList) {
                    openList(p);
                }
            }
            case ADMIN_MAIN -> {
                if (!admin(p)) return;
                if (s == 11) openCrates(p);
                else if (s == 15) openKeys(p);
            }
            case ADMIN_CRATES -> {
                if (!admin(p)) return;
                List<Crate> list = new ArrayList<>(pl.crates.values());
                if (s == 45) {
                    openMain(p);
                } else if (s == 49) {
                    ask(p, "&eEntrez l'identifiant de la nouvelle caisse (lettres/chiffres).", m -> {
                        String id = m.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
                        if (id.isEmpty() || pl.crates.containsKey(id)) {
                            p.sendMessage(t("&cIdentifiant invalide ou déjà utilisé."));
                            return;
                        }
                        Crate c = new Crate(id, "&6" + id);
                        pl.crates.put(id, c);
                        pl.save();
                        openEdit(p, c);
                    });
                } else if (s >= 0 && s < list.size() && s < 45) {
                    Crate c = list.get(s);
                    if (e.isRightClick()) {
                        give(p, pl.blockItem(c));
                        p.sendMessage(t("&aBloc de la caisse " + c.name + " &areçu. Posez-le pour créer la caisse."));
                    } else {
                        openEdit(p, c);
                    }
                }
            }
            case ADMIN_KEYS -> {
                if (!admin(p)) return;
                List<Crate> list = new ArrayList<>(pl.crates.values());
                if (s == 45) {
                    openMain(p);
                } else if (s >= 0 && s < list.size() && s < 45 && e.isRightClick()) {
                    Crate c = list.get(s);
                    if (c.key == null) return;
                    give(p, pl.makeKey(c, e.isShiftClick() ? 16 : 1));
                }
            }
            case EDIT -> {
                if (!admin(p)) return;
                Crate c = h.crate;
                switch (s) {
                    case 4 -> give(p, pl.blockItem(c));
                    case 10 -> ask(p, "&eEntrez le nouveau nom (codes couleur &).", m -> {
                        c.name = m;
                        pl.save();
                        openEdit(p, c);
                    });
                    case 12 -> {
                        int i = BLOCKS.indexOf(c.block);
                        c.block = BLOCKS.get((i + 1) % BLOCKS.size());
                        pl.save();
                        openEdit(p, c);
                    }
                    case 14 -> {
                        if (e.isRightClick()) {
                            if (c.key != null) give(p, pl.makeKey(c, 1));
                        } else {
                            ItemStack hand = p.getInventory().getItemInMainHand();
                            if (hand.getType().isAir()) {
                                p.sendMessage(t("&cTenez l'objet qui servira de clef dans votre main."));
                            } else {
                                c.key = pl.tagKey(hand, c);
                                pl.save();
                                openEdit(p, c);
                            }
                        }
                    }
                    case 16 -> openRewards(p, c);
                    case 18 -> openCrates(p);
                    case 22 -> {
                        if (e.isShiftClick()) {
                            pl.crates.remove(c.id);
                            pl.save();
                            openCrates(p);
                        }
                    }
                    default -> { }
                }
            }
            case REWARDS -> {
                if (!admin(p)) return;
                Crate c = h.crate;
                if (s == 45) {
                    openEdit(p, c);
                } else if (s == 53) {
                    ask(p, "&eFormat : &fpoids;commande &e(ex : &f10;give %player% diamond 3&e)", m -> {
                        String[] a = m.split(";", 2);
                        try {
                            double w = Double.parseDouble(a[0].trim().replace(',', '.'));
                            if (a.length < 2 || a[1].isBlank()) throw new NumberFormatException();
                            c.rewards.add(new Reward(null, a[1].trim().replaceFirst("^/", ""), Math.max(0, w)));
                            pl.save();
                        } catch (NumberFormatException ex) {
                            p.sendMessage(t("&cFormat invalide."));
                        }
                        openRewards(p, c);
                    });
                } else if (s >= 0 && s < 45 && s < c.rewards.size()) {
                    Reward r = c.rewards.get(s);
                    ClickType ct = e.getClick();
                    switch (ct) {
                        case LEFT -> r.weight += 1;
                        case RIGHT -> r.weight = Math.max(0, r.weight - 1);
                        case SHIFT_LEFT -> r.weight += 10;
                        case SHIFT_RIGHT -> r.weight = Math.max(0, r.weight - 10);
                        case DROP, CONTROL_DROP -> c.rewards.remove(r);
                        case MIDDLE -> {
                            ask(p, "&eEntrez le nouveau poids (nombre).", m -> {
                                try {
                                    r.weight = Math.max(0, Double.parseDouble(m.trim().replace(',', '.')));
                                    pl.save();
                                } catch (NumberFormatException ex) {
                                    p.sendMessage(t("&cNombre invalide."));
                                }
                                openRewards(p, c);
                            });
                            return;
                        }
                        default -> { return; }
                    }
                    pl.save();
                    fillRewards(e.getInventory(), c);
                }
            }
            default -> { }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof H h && h.type == Type.ANIM && !h.delivered) {
            h.delivered = true;
            if (h.task != null) h.task.cancel();
            deliver(h.player, h.crate, h.winner);
        }
    }

    // ---------- Saisie dans le chat ----------

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Consumer<String> cb = pending.remove(e.getPlayer().getUniqueId());
        if (cb == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> {
            if (msg.equalsIgnoreCase("annuler")) p.sendMessage(t("&7Annulé."));
            else cb.accept(msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Blocs de caisse dans le monde ----------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemMeta m = e.getItemInHand().getItemMeta();
        if (m == null) return;
        String id = m.getPersistentDataContainer().get(pl.blockTag, PersistentDataType.STRING);
        if (id == null) return;
        Crate c = pl.crates.get(id);
        if (c == null || !e.getPlayer().hasPermission("caisse.admin")) {
            e.setCancelled(true);
            return;
        }
        c.blocks.add(CaissePlugin.bk(e.getBlock()));
        pl.save();
        e.getPlayer().sendMessage(t("&aCaisse " + c.name + " &aposée. Clic droit / gauche dessus pour l'ouvrir."));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Crate c = pl.crateAt(b);
        if (c == null) return;
        Player p = e.getPlayer();
        // un admin accroupi peut casser la caisse avec un clic gauche
        if (a == Action.LEFT_CLICK_BLOCK && p.isSneaking() && p.hasPermission("caisse.admin")) return;
        e.setCancelled(true);
        if (!p.hasPermission("caisse.use")) return;
        openPreview(p, c, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Crate c = pl.crateAt(e.getBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        if (p.isSneaking() && p.hasPermission("caisse.admin")) {
            c.blocks.remove(CaissePlugin.bk(e.getBlock()));
            pl.save();
            e.setDropItems(false);
            p.sendMessage(t("&cPoint d'ouverture de la caisse " + c.name + " &csupprimé."));
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> pl.crateAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> pl.crateAt(b) != null);
    }
}
