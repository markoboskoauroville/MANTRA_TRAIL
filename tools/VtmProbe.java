import org.oscim.tiling.source.mapfile.MapFileTileSource;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.ITileDataSink;
import org.oscim.tiling.QueryResult;
import org.oscim.core.MapElement;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import java.io.FileInputStream;

/** Does VTM open the same croatia.map that mapsforge read without complaint? */
public class Probe {
    public static void main(String[] args) throws Exception {
        MapFileTileSource source = new MapFileTileSource();
        boolean set = source.setMapFile("/tmp/croatia.map");
        System.out.println("setMapFile: " + set);
        System.out.println("open: " + source.open());
        System.out.println("info: " + source.getMapInfo().boundingBox);

        ITileDataSource db = source.getDataSource();
        int z = 17;
        double lat = 45.815, lon = 15.982;
        int x = (int) Math.floor((lon + 180) / 360 * (1 << z));
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * (1 << z));
        final int[] count = {0};
        ITileDataSink sink = new ITileDataSink() {
            public void process(MapElement element) { count[0]++; }
            public void setTileImage(org.oscim.backend.canvas.Bitmap bitmap) { }
            public void completed(QueryResult result) { System.out.println("query: " + result); }
        };
        MapTile tile = new MapTile(x, y, (byte) z);
        db.query(tile, sink);
        System.out.println("elements at z" + z + ": " + count[0]);
    }
}
