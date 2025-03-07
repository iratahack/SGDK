package sgdk.rescomp.resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import sgdk.rescomp.Resource;
import sgdk.rescomp.tool.Util;
import sgdk.rescomp.type.Basics.Compression;
import sgdk.rescomp.type.Basics.TileEquality;
import sgdk.rescomp.type.Basics.TileOptimization;
import sgdk.rescomp.type.Basics.TileOrdering;
import sgdk.rescomp.type.Tile;
import sgdk.tool.ArrayUtil;
import sgdk.tool.ImageUtil;
import sgdk.tool.ImageUtil.BasicImageInfo;

public class Tilemap extends Resource
{
    public static Tilemap getTilemap(String id, Tileset tileset, int mapBase, byte[] image8bpp, int imageWidth, int imageHeight, int startTileX, int startTileY,
            int widthTile, int heightTile, TileOptimization opt, Compression compression, TileOrdering order)
    {
        final int w = widthTile;
        final int h = heightTile;

        final boolean mapBasePrio = (mapBase & Tile.TILE_PRIORITY_MASK) != 0;
        final int mapBasePal = (mapBase & Tile.TILE_PALETTE_MASK) >> Tile.TILE_PALETTE_SFT;
        final int mapBaseTileInd = mapBase & Tile.TILE_INDEX_MASK;
        // we have a base offset --> we can use system plain tiles
        final boolean useSystemTiles = mapBaseTileInd != 0;

        final short[] data = new short[w * h];

        int offset = 0;
        for (int j = 0; j < h; j++)
        {
            for (int i = 0; i < w; i++)
            {
                // tile position
                final int ti = i + startTileX;
                final int tj = j + startTileY;

                // get tile
                final Tile tile = Tile.getTile(image8bpp, imageWidth, imageHeight, ti * 8, tj * 8, 8);
                int index;
                TileEquality equality = TileEquality.NONE;

                // if no optimization, just use current offset as index
                if (opt == TileOptimization.NONE)
                {
                    // important to respect tile ordering when computing index
                    if (order == TileOrdering.ROW)
                    {
                        index = ((j * w) + i) + mapBaseTileInd;
                    }
                    else
                    {
                        index = ((i * h) + j) + mapBaseTileInd;
                    }
                }
                else
                {
                    // use system tiles for plain tiles if possible
                    if (useSystemTiles && tile.isPlain())
                        index = tile.getPlainValue();
                    else
                    {
                        // otherwise we try to get tile index in the tileset
                        index = tileset.getTileIndex(tile, opt);
                        // not found ? (should never happen)
                        if (index == -1)
                            throw new RuntimeException("Can't find tile [" + ti + "," + tj + "] in tileset, something wrong happened...");

                        // get equality info
                        equality = tile.getEquality(tileset.get(index));
                        // can add base index now
                        index += mapBaseTileInd;
                    }
                }

                // set tilemap
                data[offset++] = (short) Tile.TILE_ATTR_FULL(mapBasePal + tile.pal, mapBasePrio | tile.prio, equality.vflip, equality.hflip, index);
            }
        }

        return new Tilemap(id, data, w, h, compression);
    }

    public static Tilemap getTilemap(String id, Tileset tileset, int mapBase, byte[] image8bpp, int widthTile, int heightTile, TileOptimization opt,
            Compression compression, TileOrdering order)
    {
        return getTilemap(id, tileset, mapBase, image8bpp, widthTile * 8, heightTile * 8, 0, 0, widthTile, heightTile, opt, compression, order);
    }

    public static Tilemap getTilemap(String id, Tileset tileset, int mapBase, String imgFile, TileOptimization tileOpt, Compression compression, TileOrdering order)
            throws Exception
    {
        // get 8bpp pixels and also check image dimension is aligned to tile
        final byte[] image = ImageUtil.getImageAs8bpp(imgFile, true, true);

        // happen when we couldn't retrieve palette data from RGB image
        if (image == null)
            throw new IllegalArgumentException(
                    "RGB image '" + imgFile + "' does not contains palette data (see 'Important note about image format' in the rescomp.txt file");

        // retrieve basic infos about the image
        final BasicImageInfo imgInfo = ImageUtil.getBasicInfo(imgFile);
        final int w = imgInfo.w;
        // we determine 'h' from data length and 'w' as we can crop image vertically to remove palette data
        final int h = image.length / w;

        // b0-b3 = pixel data; b4-b5 = palette index; b7 = priority bit
        // build TILEMAP with wanted compression
        return Tilemap.getTilemap(id, tileset, mapBase, image, w / 8, h / 8, tileOpt, compression, order);
    }

    public final int w;
    public final int h;
    final int hc;

    // binary data for tilemap
    public final Bin bin;

    public Tilemap(String id, short[] data, int w, int h, Compression compression)
    {
        super(id);

        this.w = w;
        this.h = h;

        // build BIN (tilemap data) with wanted compression
        final Bin binResource = new Bin(id + "_data", data, compression);

        // add as resource (avoid duplicate)
        bin = (Bin) addInternalResource(binResource);

        // compute hash code
        hc = bin.hashCode() ^ (w << 8) ^ (h << 16);
    }

    // public Tilemap(String id, Tilemap tilemap, Compression compression)
    // {
    // this(id, tilemap.getData(), tilemap.w, tilemap.h, compression, true);
    // }
    // }

    public short[] getData()
    {
        return ArrayUtil.byteToShort(bin.data);
    }

    public void setData(short[] data)
    {
        if (data.length != (w * h))
            throw new RuntimeException("Tilemap.setData(..): size do not match !");

        ArrayUtil.shortToByte(data, 0, bin.data, 0, bin.data.length, false);
    }

    @Override
    public int internalHashCode()
    {
        return hc;

    }

    @Override
    public boolean internalEquals(Object obj)
    {
        if (obj instanceof Tilemap)
        {
            final Tilemap tilemap = (Tilemap) obj;
            return (w == tilemap.w) && (h == tilemap.h) && bin.equals(tilemap.bin);
        }

        return false;
    }

    @Override
    public List<Bin> getInternalBinResources()
    {
        return Arrays.asList(bin);
    }

    @Override
    public int shallowSize()
    {
        return 2 + 2 + 2 + 4;
    }

    @Override
    public int totalSize()
    {
        return bin.totalSize() + shallowSize();
    }

    @Override
    public void out(ByteArrayOutputStream outB, StringBuilder outS, StringBuilder outH) throws IOException
    {
        // can't store pointer so we just reset binary stream here (used for compression only)
        outB.reset();

        // output TileMap structure
        Util.decl(outS, outH, "TileMap", id, 2, global);
        // set compression info (very important that binary data had already been exported at this point)
        outS.append("    dc.w    " + (bin.doneCompression.ordinal() - 1) + "\n");
        // set size in tile
        outS.append("    dc.w    " + w + ", " + h + "\n");
        // set data pointer
        outS.append("    dc.l    " + bin.id + "\n");
        outS.append("\n");
    }
}