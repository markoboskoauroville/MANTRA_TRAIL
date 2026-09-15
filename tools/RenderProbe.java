import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.renderer.DatabaseRenderer;
import org.mapsforge.map.layer.renderer.RendererJob;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * THE Z19 ROADBLOCK, ON A DESK.
 *
 * The phone says the offline map is blank above zoom 18 and nobody could say why from a
 * screenshot. So the same file, the same library version and the same theme are run here without
 * Android in the way: render one tile over Zagreb at each zoom and count what the file returned
 * and what the renderer drew.
 *
 * Two numbers per zoom settle it. Ways and POIs read from the file say whether the DATA is there.
 * Distinct colours in the rendered tile say whether the RENDERER drew it. If the data is there
 * and the tile is one colour, the fault is the theme or the renderer, and no amount of cache
 * tuning on the phone would ever have fixed it.
 */
public class RenderProbe {

    public static void main(String[] args) throws Exception {
        double lat = 45.8150;
        double lon = 15.9819;
        MapFile mapFile = new MapFile(new File("/tmp/croatia.map"));
        AwtGraphicFactory factory = (AwtGraphicFactory) AwtGraphicFactory.INSTANCE;
        DisplayModel displayModel = new DisplayModel();
        displayModel.setFixedTileSize(256);

        RenderThemeFuture theme = new RenderThemeFuture(factory, MapsforgeThemes.DEFAULT, displayModel);
        new Thread(theme).start();

        InMemoryTileCache cache = new InMemoryTileCache(64);
        DatabaseRenderer renderer =
                new DatabaseRenderer(mapFile, factory, cache, null, true, true, null);

        System.out.println("zoom |   ways |   pois | colours | verdict");
        for (int z = 14; z <= 21; z++) {
            int x = (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
            double latRad = Math.toRadians(lat);
            int y = (int) Math.floor(
                    (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * (1 << z));
            Tile tile = new Tile(x, y, (byte) z, 256);

            MapReadResult read = mapFile.readMapData(tile);
            int ways = read == null ? -1 : read.ways.size();
            int pois = read == null ? -1 : read.pois.size();

            RendererJob job = new RendererJob(tile, mapFile, theme, displayModel, 1f, false, false);
            TileBitmap bitmap = renderer.executeJob(job);
            int colours = -1;
            if (bitmap != null) {
                BufferedImage image = AwtGraphicFactory.getBitmap(bitmap);
                java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                for (int px = 0; px < image.getWidth(); px += 2) {
                    for (int py = 0; py < image.getHeight(); py += 2) {
                        seen.add(image.getRGB(px, py));
                    }
                }
                colours = seen.size();
            }
            String verdict = colours <= 1 ? "BLANK" : "drawn";
            System.out.printf("  %2d | %6d | %6d | %7d | %s%n", z, ways, pois, colours, verdict);
        }
        mapFile.close();
    }
}
