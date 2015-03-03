package com.worldcretornica.dynmapplotme;

import com.worldcretornica.plotme_core.Plot;
import com.worldcretornica.plotme_core.PlotId;
import com.worldcretornica.plotme_core.PlotMeCoreManager;
import com.worldcretornica.plotme_core.api.IWorld;
import com.worldcretornica.plotme_core.bukkit.PlotMe_CorePlugin;
import com.worldcretornica.plotme_core.bukkit.api.BukkitLocation;
import com.worldcretornica.plotme_core.bukkit.api.BukkitWorld;
import com.worldcretornica.plotme_core.bukkit.event.PlotEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DynmapPlotMe extends JavaPlugin {

    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">ID : %plotid%</span><br />" +
                                                 " Owner <span style=\"font-weight:bold;\">%plotowners%</span>%plothelpers%";
    private static MarkerSet set;
    public PlotMe_CorePlugin plotme;
    public MarkerAPI markerapi;
    public Plugin dynmap;
    public DynmapAPI api;
    private long updperiod;
    // private boolean use3d;
    private String infowindow;
    private AreaStyle defstyle;
    private Map<String, AreaStyle> cusstyle;
    private Map<String, AreaStyle> cuswildstyle;
    private Map<String, AreaStyle> ownerstyle;
    private Set<String> visible;
    private Set<String> hidden;
    private boolean stop;
    private Map<String, AreaMarker> resareas = new HashMap<>();
    private PlotMeCoreManager manager;

    public void severe(String msg) {
        getLogger().severe("[DynmapPlotMe] " + msg);
    }

    private String formatInfoWindow(Plot plot) {
        String v = "<div class=\"plotinfo\">" + infowindow + "</div>";
        v = v.replace("%plotid%", plot.getId().toString());
        v = v.replace("%plotowners%", plot.getOwner());
        if (plot.getAllowed().equalsIgnoreCase("")) {
            v = v.replace("%plothelpers%", "");
        } else {
            v = v.replace("%plothelpers%", "<br />Helpers <span style=\"font-weight:bold;\">" + plot.getAllowed() + "</span></div>");
        }
        return v;
    }

    private boolean isVisible(String id, String worldname) {
        if (visible != null && !visible.isEmpty()) {
            if (!visible.contains(id) && !visible.contains("world:" + worldname) &&
                    !visible.contains(worldname + "/" + id)) {
                return false;
            }
        }
        return !(hidden != null && !hidden.isEmpty() && (hidden.contains(id) || hidden.contains("world:" + worldname) || hidden
                .contains(worldname + "/" + id)));
    }

    private void addStyle(String plotid, String worldid, AreaMarker marker, Plot plot) {
        AreaStyle as = cusstyle.get(worldid + "/" + plotid);
        if (as == null) {
            as = cusstyle.get(plotid);
        }
        if (as == null) {    /* Check for wildcard style matches */
            for (String wc : cuswildstyle.keySet()) {
                String[] tok = wc.split("\\|");
                if (tok.length == 1 && plotid.startsWith(tok[0]) || tok.length >= 2 && plotid.startsWith(tok[0]) && plotid.endsWith(tok[1])) {
                    as = cuswildstyle.get(wc);
                }
            }
        }
        if (as == null) {    /* Check for owner style matches */
            if (!ownerstyle.isEmpty()) {
                String owner = plot.getOwner();
                as = ownerstyle.get(owner.toLowerCase());
            }
        }
        if (as == null) {
            as = defstyle;
        }

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException ignored) {
        }
        marker.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        marker.setFillStyle(as.fillopacity, fc);

        if (as.label != null) {
            marker.setLabel(as.label);
        }
    }

    /**
     * @param world
     * @param plot
     * @param newmap
     */
    private void handlePlot(World world, Plot plot, Map<String, AreaMarker> newmap) {
        PlotId name = plot.getId();

        /* Handle areas */
        if (isVisible(name.toString(), world.getName())) {

            Location bottom = ((BukkitLocation) manager.getPlotBottomLoc(new BukkitWorld(world), name)).getLocation();
            Location top = ((BukkitLocation) manager.getPlotTopLoc(new BukkitWorld(world), name)).getLocation();

            int roadheight = plotme.getAPI().getGenManager(world.getName()).getRoadHeight(world.getName());

            bottom.setY(roadheight);
            top.setY(roadheight);

            /* Make outline */
            double[] x = new double[4];
            double[] z = new double[4];
            x[0] = bottom.getX() - 2.0;
            z[0] = bottom.getZ() - 2.0;
            x[1] = bottom.getX() - 2.0;
            z[1] = top.getZ() + 2.0;
            x[2] = top.getX() + 2.0;
            z[2] = top.getZ() + 2.0;
            x[3] = top.getX() + 2.0;
            z[3] = bottom.getZ() - 2.0;

            String markerid = world.getName() + "_" + name;
            AreaMarker marker = resareas.remove(markerid); /* Existing area? */
            if (marker == null) {
                marker = set.createAreaMarker(markerid, name.toString(), false, world.getName(), x, z, false);
                if (marker == null) {
                    return;
                }
            } else {
                marker.setCornerLocations(x, z); /* Replace corner locations */
                marker.setLabel(name.toString());   /* Update label */
            }
            //if(use3d) { /* If 3D? */
            marker.setRangeY(top.getY(), bottom.getY());
            //}
            /* Set line and fill properties */
            addStyle(name.toString(), world.getName(), marker, plot);

            /* Build popup */
            String desc = formatInfoWindow(plot);

            marker.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, marker);
        }
    }

    /* Handle specific region */

    /* Update plotme information */
    private void updatePlots() {
        Map<String, AreaMarker> newmap = new HashMap<>(); /* Build new map */

        /* Loop through worlds */
        for (IWorld world1 : plotme.getAPI().getServerBridge().getWorlds()) {
            BukkitWorld world = (BukkitWorld) world1;
            if (manager.isPlotWorld(world)) {
                ConcurrentHashMap<PlotId, Plot> plots = manager.getMap(world).getLoadedPlots();

                for (Plot plot : plots.values()) {
                    handlePlot(world.getWorld(), plot, newmap);
                }
            }
        }
        /* Now, review old map - anything left is gone */
        for (AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;

        getServer().getScheduler().scheduleSyncDelayedTask(this, new PlotMeUpdate(), updperiod);
    }

    @Override
    public void onEnable() {
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if (dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI) dynmap; /* Get API */
        /* Get WorldGuard */
        Plugin p = pm.getPlugin("PlotMe");
        if (p == null) {
            severe("Cannot find PlotMe-Core!");
            return;
        }
        plotme = (PlotMe_CorePlugin) p;
        manager = PlotMeCoreManager.getInstance();
        getServer().getPluginManager().registerEvents(new OurServerListener(), this);
        /* If both enabled, activate */

        if (dynmap.isEnabled() && plotme.isEnabled()) {
            activate();
        }
    }

    public void activate() {

        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if (markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        saveConfig();  /* Save updates, if needed */

        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("plotme.markerset");
        if (set == null) {
            set = markerapi.createMarkerSet("plotme.markerset", cfg.getString("layer.name", "PlotMe"), null, false);
        } else {
            set.setMarkerSetLabel(cfg.getString("layer.name", "PlotMe"));
        }
        if (set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if (minzoom > 0) {
            set.setMinZoom(minzoom);
        }
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        //use3d = cfg.getBoolean("use3dregions", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "plotstyle");
        cusstyle = new HashMap<>();
        ownerstyle = new HashMap<>();
        cuswildstyle = new HashMap<>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if (sect != null) {
            Set<String> ids = sect.getKeys(false);

            for (String id : ids) {
                if (id.indexOf('|') >= 0) {
                    cuswildstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
                } else {
                    cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
                }
            }
        }
        sect = cfg.getConfigurationSection("ownerstyle");
        if (sect != null) {
            Set<String> ids = sect.getKeys(false);

            for (String id : ids) {
                ownerstyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visibleplots");
        if (vis != null) {
            visible = new HashSet<>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenplots");
        if (hid != null) {
            hidden = new HashSet<>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if (per < 15) {
            per = 15;
        }
        updperiod = per * 20;
        stop = false;

        getServer().getScheduler().scheduleSyncDelayedTask(this, new PlotMeUpdate(), 40);   /* First time is 2 seconds */
    }

    private static class AreaStyle {

        String strokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String label;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path + ".strokeColor", def.strokecolor);
            strokeopacity = cfg.getDouble(path + ".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path + ".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path + ".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path + ".fillOpacity", def.fillopacity);
            label = cfg.getString(path + ".label", null);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path + ".strokeColor", "#FF0000");
            strokeopacity = cfg.getDouble(path + ".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path + ".strokeWeight", 8);
            fillcolor = cfg.getString(path + ".fillColor", "#FFFFFF");
            fillopacity = cfg.getDouble(path + ".fillOpacity", 0.01);
        }
    }

    private class PlotMeUpdate implements Runnable {

        @Override
        public void run() {
            if (!stop) {
                updatePlots();
            }
        }
    }

    private class OurServerListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if (name.equals("dynmap")) {
                if (dynmap.isEnabled() && plotme.isEnabled()) {
                    activate();
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPlotEvent(PlotEvent event) {
            Plot plot = event.getPlot();
            World w = event.getWorld();

            if (plot != null && w != null) {
                Map<String, AreaMarker> newmap = new HashMap<>();

                handlePlot(w, plot, newmap);
            }
        }
    }

}