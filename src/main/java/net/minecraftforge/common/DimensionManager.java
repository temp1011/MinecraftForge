/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Hashtable;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntListIterator;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Multiset;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.DimensionType;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.ServerWorldEventHandler;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLLog;

import javax.annotation.Nullable;

public class DimensionManager
{
    private static class Dimension
    {
        private final DimensionType type;
        private int ticksWaited;
        private Dimension(DimensionType type)
        {
            this.type = type;
            this.ticksWaited = 0;
        }
    }

    private static Hashtable<Integer, WorldServer> worlds = new Hashtable<Integer, WorldServer>();
    private static boolean hasInit = false;
    private static Hashtable<Integer, Dimension> dimensions = new Hashtable<Integer, Dimension>();
    private static BiMap<String, Integer> dimensionIDMap = HashBiMap.create();
    private static IntArrayList unloadQueue = new IntArrayList();
    private static BitSet dimensionMap = new BitSet(Long.SIZE << 4);
    private static ConcurrentMap<World, World> weakWorldMap = new MapMaker().weakKeys().weakValues().<World,World>makeMap();
    private static Multiset<Integer> leakedWorlds = HashMultiset.create();

    /**
     * Returns a list of dimensions associated with this DimensionType.
     */
    public static int[] getDimensions(DimensionType type)
    {
        int[] ret = new int[dimensionMap.size()];
        int x = 0;
        for (Map.Entry<Integer, Dimension> ent : dimensions.entrySet())
        {
            if (ent.getValue().type == type)
            {
                ret[x++] = ent.getKey();
            }
        }

        return Arrays.copyOf(ret, x);
    }
    
    /**
     * Returns a list of dimensions associated with this DimensionType.
     */
    public static String[] getStringDimensions(DimensionType type)
    {
    	String[] ret = new String[dimensionMap.size()];
        int x = 0;
        for (Map.Entry<Integer, Dimension> ent : dimensions.entrySet())
        {
            if (ent.getValue().type == type)
            {
                ret[x++] = dimensionIDMap.inverse().get(ent.getKey());
            }
        }

        return Arrays.copyOf(ret, x);

    }

    public static void init()
    {
        if (hasInit)
        {
            return;
        }

        hasInit = true;

        registerDimension( 0, DimensionType.OVERWORLD, "minecraft.overworld");
        registerDimension(-1, DimensionType.NETHER, "minecraft.nether");
        registerDimension( 1, DimensionType.THE_END, "minecraft.end");
    }

    public static void registerDimension(int id, DimensionType type)
    {
        registerDimension(id,type,"noModid:dimension" + id);
    }
    
    public static void registerDimension(String id, DimensionType type)
    {
    	if(id.indexOf(":")==-1)
    	{
    		throw new IllegalArgumentException(String.format("Failed to register dimension for stringID id %d, please use format modid:name", id));
    	}
    	registerDimension(getNextFreeDimId(),type,id);
    }
    
    public static void registerDimension(int id, DimensionType type, String stringID)
    {
        DimensionType.getById(type.getId()); //Check if type is invalid {will throw an error} No clue how it would be invalid tho...
        if (dimensions.containsKey(id) || dimensionIDMap.containsKey(stringID))
        {
            throw new IllegalArgumentException(String.format("Failed to register dimension for id %d, One is already registered", id));
        }
        dimensions.put(id, new Dimension(type));
        dimensionIDMap.put(stringID, id);
        if (id >= 0)
        {
            dimensionMap.set(id);
        }
    }
    
    /**
     * For unregistering a dimension when the save is changed (disconnected from a server or loaded a new save
     */
    public static void unregisterDimension(int id)
    {
        if (!dimensions.containsKey(id))
        {
            throw new IllegalArgumentException(String.format("Failed to unregister dimension for id %d; No provider registered", id));
        }
        dimensions.remove(id);
        dimensionIDMap.inverse().remove(id);
    }
    
    /**
     * For unregistering a dimension when the save is changed (disconnected from a server or loaded a new save
     */
    public static void unregisterDimension(String id)
    {
        if (!dimensionIDMap.containsKey(id))
        {
            throw new IllegalArgumentException(String.format("Failed to unregister dimension for id %d; No provider registered", id));
        }
        dimensions.remove(dimensionIDMap.get(id));
        dimensionIDMap.remove(id);
    }

    public static boolean isDimensionRegistered(int dim)
    {
        return dimensions.containsKey(dim);
    }
    
    public static boolean isDimensionRegistered(String dim)
    {
        return dimensionIDMap.containsKey(dim);
    }

    public static DimensionType getProviderType(int dim)
    {
        if (!dimensions.containsKey(dim))
        {
            throw new IllegalArgumentException(String.format("Could not get provider type for dimension %d, does not exist", dim));
        }
        return dimensions.get(dim).type;
    }
    
    public static DimensionType getProviderType(String dim)
    {
        if (!dimensionIDMap.containsKey(dim))
        {
            throw new IllegalArgumentException("Could not get provider type for dimension " + dim + ", does not exist");
        }
        return dimensions.get(dimensionIDMap.get(dim)).type;
    }

    public static WorldProvider getProvider(int dim)
    {
        return getWorld(dim).provider;
    }
    
    public static WorldProvider getProvider(String dim)	//patch so can't be fully implemented
    {
        return getWorld(dimensionIDMap.get(dim)).provider;
    }

    public static Integer[] getIDs(boolean check)
    {
        if (check)
        {
            List<World> allWorlds = Lists.newArrayList(weakWorldMap.keySet());
            allWorlds.removeAll(worlds.values());
            for (ListIterator<World> li = allWorlds.listIterator(); li.hasNext(); )
            {
                World w = li.next();
                leakedWorlds.add(System.identityHashCode(w));
            }
            for (World w : allWorlds)
            {
                int leakCount = leakedWorlds.count(System.identityHashCode(w));
                if (leakCount == 5)
                {
                    FMLLog.log.debug("The world {} ({}) may have leaked: first encounter (5 occurrences).\n", Integer.toHexString(System.identityHashCode(w)), w.getWorldInfo().getWorldName());
                }
                else if (leakCount % 5 == 0)
                {
                    FMLLog.log.debug("The world {} ({}) may have leaked: seen {} times.\n", Integer.toHexString(System.identityHashCode(w)), w.getWorldInfo().getWorldName(), leakCount);
                }
            }
        }
        return getIDs();
    }
    
    public static Integer[] getIDs()
    {
        return worlds.keySet().toArray(new Integer[worlds.size()]); //Only loaded dims, since usually used to cycle through loaded worlds
    }

    public static void setWorld(int id, @Nullable WorldServer world, MinecraftServer server)
    {
        if (world != null)
        {
            worlds.put(id, world);
            weakWorldMap.put(world, world);
            server.worldTickTimes.put(id, new long[100]);
            FMLLog.log.info("Loading dimension {} ({}) ({})", id, world.getWorldInfo().getWorldName(), world.getMinecraftServer());
        }
        else
        {
            worlds.remove(id);
            server.worldTickTimes.remove(id);
            FMLLog.log.info("Unloading dimension {}", id);
        }

        ArrayList<WorldServer> tmp = new ArrayList<WorldServer>();
        if (worlds.get( 0) != null)
            tmp.add(worlds.get( 0));
        if (worlds.get(-1) != null)
            tmp.add(worlds.get(-1));
        if (worlds.get( 1) != null)
            tmp.add(worlds.get( 1));

        for (Entry<Integer, WorldServer> entry : worlds.entrySet())
        {
            int dim = entry.getKey();
            if (dim >= -1 && dim <= 1)
            {
                continue;
            }
            tmp.add(entry.getValue());
        }

        server.worlds = tmp.toArray(new WorldServer[tmp.size()]);
    }
    
    public static void setWorld(String id, @Nullable WorldServer world, MinecraftServer server)
    {
        if (world != null)
        {
            worlds.put(dimensionIDMap.get(id), world);
            weakWorldMap.put(world, world);
            server.worldTickTimes.put(dimensionIDMap.get(id), new long[100]);
            FMLLog.log.info("Loading dimension {} ({}) ({})", id, world.getWorldInfo().getWorldName(), world.getMinecraftServer());
        }
        else
        {
            worlds.remove(dimensionIDMap.get(id));
            server.worldTickTimes.remove(dimensionIDMap.get(id));
            FMLLog.log.info("Unloading dimension {}", id);
        }

        ArrayList<WorldServer> tmp = new ArrayList<WorldServer>();
        if (worlds.get( 0) != null)
            tmp.add(worlds.get( 0));
        if (worlds.get(-1) != null)
            tmp.add(worlds.get(-1));
        if (worlds.get( 1) != null)
            tmp.add(worlds.get( 1));

        for (Entry<Integer, WorldServer> entry : worlds.entrySet())
        {
            int dim = entry.getKey();
            if (dim >= -1 && dim <= 1)
            {
                continue;
            }
            tmp.add(entry.getValue());
        }

        server.worlds = tmp.toArray(new WorldServer[tmp.size()]);
    }

    public static void initDimension(int dim)
    {
        WorldServer overworld = getWorld(0);
        if (overworld == null)
        {
            throw new RuntimeException("Cannot Hotload Dim: Overworld is not Loaded!");
        }
        try
        {
            DimensionManager.getProviderType(dim);
        }
        catch (Exception e)
        {
            FMLLog.log.error("Cannot Hotload Dim: {}", dim, e);
            return; // If a provider hasn't been registered then we can't hotload the dim
        }
        MinecraftServer mcServer = overworld.getMinecraftServer();
        ISaveHandler savehandler = overworld.getSaveHandler();
        //WorldSettings worldSettings = new WorldSettings(overworld.getWorldInfo());

        WorldServer world = (dim == 0 ? overworld : (WorldServer)(new WorldServerMulti(mcServer, savehandler, dim, overworld, mcServer.profiler).init()));
        world.addEventListener(new ServerWorldEventHandler(mcServer, world));
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(world));
        if (!mcServer.isSinglePlayer())
        {
            world.getWorldInfo().setGameType(mcServer.getGameType());
        }

        mcServer.setDifficultyForAllWorlds(mcServer.getDifficulty());
    }
    
    public static void initDimension(String dim)
    {
        WorldServer overworld = getWorld(0);
        if (overworld == null)
        {
            throw new RuntimeException("Cannot Hotload Dim: Overworld is not Loaded!");
        }
        try
        {
            DimensionManager.getProviderType(dim);
        }
        catch (Exception e)
        {
            FMLLog.log.error("Cannot Hotload Dim: {}", dim, e);
            return; // If a provider hasn't been registered then we can't hotload the dim
        }
        MinecraftServer mcServer = overworld.getMinecraftServer();
        ISaveHandler savehandler = overworld.getSaveHandler();
        //WorldSettings worldSettings = new WorldSettings(overworld.getWorldInfo());

        WorldServer world = (dim.equals("minecraft.overworld") ? overworld : (WorldServer)(new WorldServerMulti(mcServer, savehandler, dimensionIDMap.get(dim), overworld, mcServer.profiler).init()));
        world.addEventListener(new ServerWorldEventHandler(mcServer, world));
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(world));
        if (!mcServer.isSinglePlayer())
        {
            world.getWorldInfo().setGameType(mcServer.getGameType());
        }

        mcServer.setDifficultyForAllWorlds(mcServer.getDifficulty());
    }

    public static WorldServer getWorld(int id)
    {
        return worlds.get(id);
    }
    
    public static WorldServer getWorld(String id)
    {
        return worlds.get(dimensionIDMap.get(id));
    }

    public static WorldServer[] getWorlds()
    {
        return worlds.values().toArray(new WorldServer[worlds.size()]);
    }

    static
    {
        init();
    }

    /**
     * Not public API: used internally to get dimensions that should load at
     * server startup
     */
    public static Integer[] getStaticDimensionIDs()	//will need changing if full rollout to string
    {
        return dimensions.keySet().toArray(new Integer[dimensions.keySet().size()]);
    }
    
    public static WorldProvider createProviderFor(int dim)
    {
        try
        {
            if (dimensions.containsKey(dim))
            {
                WorldProvider ret = getProviderType(dim).createDimension();
                ret.setDimension(dim);
                return ret;
            }
            else
            {
                throw new RuntimeException(String.format("No WorldProvider bound for dimension %d", dim)); //It's going to crash anyway at this point.  Might as well be informative
            }
        }
        catch (Exception e)
        {
            FMLLog.log.error("An error occurred trying to create an instance of WorldProvider {} ({})",
                    dim, getProviderType(dim), e);
            throw new RuntimeException(e);
        }
    }
    
    public static WorldProvider createProviderFor(String dim)
    {
        try
        {
            if (dimensions.containsKey(dimensionIDMap.get(dim)))
            {
                WorldProvider ret = getProviderType(dim).createDimension();
                ret.setDimension(dimensionIDMap.get(dim));
                return ret;
            }
            else
            {
                throw new RuntimeException("No WorldProvider bound for dimension "+ dim); //It's going to crash anyway at this point.  Might as well be informative
            }
        }
        catch (Exception e)
        {
            FMLLog.log.error("An error occurred trying to create an instance of WorldProvider {} ({})",
                    dim, getProviderType(dim), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Queues a dimension to unload.
     * If the dimension is already queued, it will reset the delay to unload
     * @param id The id of the dimension
     */
    public static void unloadWorld(int id)
    {
        if(!unloadQueue.contains(id))
        {
            FMLLog.log.debug("Queueing dimension {} to unload", id);
            unloadQueue.add(id);
        }
        else
        {
            dimensions.get(id).ticksWaited = 0;
        }
    }
    
    /**
     * Queues a dimension to unload.
     * If the dimension is already queued, it will reset the delay to unload
     * @param id The id of the dimension
     */
    public static void unloadWorld(String id)
    {
        if(!unloadQueue.contains(dimensionIDMap.get(id)))
        {
            FMLLog.log.debug("Queueing dimension {} to unload", id);
            unloadQueue.add(dimensionIDMap.get(id));
        }
        else
        {
            dimensions.get(dimensionIDMap.get(id)).ticksWaited = 0;
        }
    }

    public static boolean isWorldQueuedToUnload(int id)
    {
        return unloadQueue.contains(id);
    }
    
    public static boolean isWorldQueuedToUnload(String id)
    {
        return unloadQueue.contains(dimensionIDMap.get(id));
    }

    /*
    * To be called by the server at the appropriate time, do not call from mod code.
    */
    public static void unloadWorlds(Hashtable<Integer, long[]> worldTickTimes) {
        IntListIterator queueIterator = unloadQueue.iterator();
        while (queueIterator.hasNext()) {
            int id = queueIterator.next();
            Dimension dimension = dimensions.get(id);
            if (dimension.ticksWaited < ForgeModContainer.dimensionUnloadQueueDelay)
            {
                dimension.ticksWaited++;
                continue;
            }
            WorldServer w = worlds.get(id);
            queueIterator.remove();
            dimension.ticksWaited = 0;
            if (w == null || !ForgeChunkManager.getPersistentChunksFor(w).isEmpty() || !w.playerEntities.isEmpty() || dimension.type.shouldLoadSpawn()) //Don't unload the world if the status changed
            {
                FMLLog.log.debug("Aborting unload for dimension {} as status changed", id);
                continue;
            }
            try
            {
                w.saveAllChunks(true, null);
            }
            catch (MinecraftException e)
            {
                FMLLog.log.error("Caught an exception while saving all chunks:", e);
            }
            finally
            {
                MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(w));
                w.flush();
                setWorld(id, null, w.getMinecraftServer());
            }
        }
    }
    

    /**
     * Return the next free dimension ID. Note: you are not guaranteed a contiguous
     * block of free ids. Always call for each individual ID you wish to get.
     * @return the next free dimension ID
     */
    public static int getNextFreeDimId() {
        int next = 0;
        while (true)
        {
            next = dimensionMap.nextClearBit(next);
            if (dimensions.containsKey(next))
            {
                dimensionMap.set(next);
            }
            else
            {
                return next;
            }
        }
    }

    public static NBTTagCompound saveDimensionDataMap()
    {
        int[] data = new int[(dimensionMap.length() + Integer.SIZE - 1 )/ Integer.SIZE];
        NBTTagCompound dimMap = new NBTTagCompound();
        for (int i = 0; i < data.length; i++)
        {
            int val = 0;
            for (int j = 0; j < Integer.SIZE; j++)
            {
                val |= dimensionMap.get(i * Integer.SIZE + j) ? (1 << j) : 0;
            }
            data[i] = val;
        }
        dimMap.setIntArray("DimensionArray", data);
        return dimMap;
    }

    public static void loadDimensionDataMap(@Nullable NBTTagCompound compoundTag)
    {
        dimensionMap.clear();
        if (compoundTag == null)
        {
            for (Integer id : dimensions.keySet())
            {
                if (id >= 0)
                {
                    dimensionMap.set(id);
                }
            }
        }
        else
        {
            int[] intArray = compoundTag.getIntArray("DimensionArray");
            for (int i = 0; i < intArray.length; i++)
            {
                for (int j = 0; j < Integer.SIZE; j++)
                {
                    dimensionMap.set(i * Integer.SIZE + j, (intArray[i] & (1 << j)) != 0);
                }
            }
        }
    }

    /**
     * Return the current root directory for the world save. Accesses getSaveHandler from the overworld
     * @return the root directory of the save
     */
    @Nullable
    public static File getCurrentSaveRootDirectory()
    {
        if (DimensionManager.getWorld(0) != null)
        {
            return DimensionManager.getWorld(0).getSaveHandler().getWorldDirectory();
        }/*
        else if (MinecraftServer.getServer() != null)
        {
            MinecraftServer srv = MinecraftServer.getServer();
            SaveHandler saveHandler = (SaveHandler) srv.getActiveAnvilConverter().getSaveLoader(srv.getFolderName(), false);
            return saveHandler.getWorldDirectory();
        }*/
        else
        {
            return null;
        }
    }
}
