package org.inventivetalent.craftingframes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.EulerAngle;
import org.inventivetalent.gson.JsonBuilder;
import org.inventivetalent.itembuilder.ItemBuilder;
import org.inventivetalent.recipebuilder.ShapedRecipeBuilder;
import org.mcstats.MetricsLite;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CraftingFrames extends JavaPlugin implements Listener {

	ItemStack craftingFrameItem;
	File dataFolder = new File(getDataFolder(), "data");

	@Override
	public void onEnable() {
		Bukkit.getPluginManager().registerEvents(this, this);

		saveDefaultConfig();
		if (!dataFolder.exists()) { dataFolder.mkdirs(); }

		new ShapedRecipeBuilder().fromConfig(getConfig().getConfigurationSection("recipe.craftingFrame")).register();
		craftingFrameItem = new ItemBuilder().fromConfig(getConfig().getConfigurationSection("recipe.craftingFrame.result")).build();

		try {
			MetricsLite metrics = new MetricsLite(this);
			if (metrics.start()) {
				getLogger().info("Metrics started");
			}
		} catch (Exception e) {
		}
	}

	@EventHandler(priority = EventPriority.MONITOR,
				  ignoreCancelled = true)
	public void on(HangingPlaceEvent event) {
		if (event.getEntity().getType() == EntityType.ITEM_FRAME) {
			if (craftingFrameItem.isSimilar(event.getPlayer().getItemInHand())) {
				event.getEntity().setMetadata("Crafting_Frame", new FixedMetadataValue(this, true));
				saveData((ItemFrame) event.getEntity());

				ArmorStand armorStand = event.getEntity().getWorld().spawn(event.getBlock().getLocation().add(0, -.5, 0).add(.5, -0.43825, .5), ArmorStand.class);
				armorStand.setMarker(true);
				armorStand.setGravity(false);
				armorStand.setVisible(false);
				armorStand.setHeadPose(new EulerAngle(Math.toRadians(90), Math.toRadians((90 * (event.getEntity().getFacing().ordinal() + 1)) + 90), 0));
				armorStand.setHelmet(new ItemStack(Material.WORKBENCH));
				armorStand.setCustomName("CraftingFrame-" + event.getEntity().getFacing() + "-" + event.getEntity().getLocation().getBlockX() + "-" + event.getEntity().getLocation().getBlockY() + "-" + event.getEntity().getLocation().getBlockZ());
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR,
				  ignoreCancelled = true)
	public void on(final HangingBreakEvent event) {
		if (event.getEntity().getType() == EntityType.ITEM_FRAME) {
			if (event.getEntity().hasMetadata("Crafting_Frame")) {
				event.getEntity().removeMetadata("Crafting_Frame", this);
				File file = getDataFile(event.getEntity().getLocation());
				file.delete();

				for (Entity entity : event.getEntity().getNearbyEntities(.75, 1.75, .75)) {
					if (entity.getType() == EntityType.ARMOR_STAND) {
						if (("CraftingFrame-" + event.getEntity().getFacing() + "-" + event.getEntity().getLocation().getBlockX() + "-" + event.getEntity().getLocation().getBlockY() + "-" + event.getEntity().getLocation().getBlockZ()).equals(entity.getCustomName())) {
							entity.remove();
						}
					}
				}
				// Workaround to replace the dropped item
				Bukkit.getScheduler().runTaskLater(this, new Runnable() {
					@Override
					public void run() {
						for (Entity entity : event.getEntity().getNearbyEntities(2, 2, 2)) {
							if (entity instanceof Item) {
								Item item = (Item) entity;
								if (item.getItemStack().getType() == Material.ITEM_FRAME) {
									if (item.getPickupDelay() >= 8) {// The delay should be 10 when dropped, but let's give it a bit more time
										item.setItemStack(craftingFrameItem.clone());
									}
								}
							}
						}
					}
				}, 1);
			}
		}
	}

	@EventHandler(priority = EventPriority.NORMAL,
				  ignoreCancelled = true)
	public void on(PlayerInteractEntityEvent event) {
		if (event.getRightClicked().getType() == EntityType.ITEM_FRAME) {
			if (!event.getPlayer().hasPermission("craftingframes.use")) { return; }

			ItemFrame itemFrame = (ItemFrame) event.getRightClicked();
			if (itemFrame.getItem() != null && itemFrame.getItem().getType() != Material.AIR && itemFrame.getItem().getAmount() > 0) {
				boolean isCraftingFrame = itemFrame.hasMetadata("Crafting_Frame");
				if (!isCraftingFrame) {
					JsonObject data = loadData(itemFrame.getLocation());
					if (isCraftingFrame = (data != null)) {
						if (itemFrame.getUniqueId().equals(UUID.fromString(data.get("uuid").getAsString()))) {
							itemFrame.setMetadata("Crafting_Frame", new FixedMetadataValue(this, true));
						} else {
							isCraftingFrame = false;
							// Invalid file -> delete
							File file = getDataFile(itemFrame.getLocation());
							file.delete();
						}
					}
				}

				if (isCraftingFrame) {
					Inventory inventory = getAttachedInventory(itemFrame);
					boolean skipExtract = false;
					if (inventory == null) {
						// Use player inventory
						inventory = event.getPlayer().getInventory();
						skipExtract = true;
					}
					if (inventory != null) {
						event.setCancelled(true);

						int toGive = event.getPlayer().isSneaking() ? 64 : 1;
						int giveCounter = 0;

						Location dropLocation = itemFrame.getLocation().getBlock().getLocation().add(.5, .5, .5);
						Location particleLocation = itemFrame.getLocation().add(itemFrame.getFacing().getModX() * 0.1, 0, itemFrame.getFacing().getModZ() * 0.1);

						if (!skipExtract) {
							for (int i = 0; i < inventory.getSize(); i++) {
								int first = inventory.first(itemFrame.getItem().getType());
								if (first >= 0) {
									ItemStack itemStack = inventory.getItem(first);
									if (itemStack.isSimilar(itemFrame.getItem())) {
										int diff = Math.min(itemStack.getAmount(), toGive - giveCounter);
										removeAmount(inventory, itemStack, diff);
										itemStack = itemStack.clone();
										itemStack.setAmount(diff);

										Item item = itemFrame.getWorld().dropItemNaturally(dropLocation, itemStack);
										item.setPickupDelay(0);

										giveCounter += itemStack.getAmount();
									}
								}
								if (giveCounter >= toGive) {
									break;
								}
							}
						}
						if (giveCounter > 0) {
							event.getPlayer().playSound(dropLocation, Sound.ENTITY_ENDERMEN_TELEPORT, 0.6f, 0.8f);
							itemFrame.getWorld().spawnParticle(Particle.DRAGON_BREATH, particleLocation, 1, 0, 0, 0, 0);
							itemFrame.getWorld().spawnParticle(Particle.PORTAL, particleLocation, 5, 0.1, 0, 0.1, 0.1);
						}
						if (giveCounter >= toGive) {
							return;
						}

						ItemStack[] contentCopy = inventory.getContents();
						int[] amounts = new int[contentCopy.length];
						for (int i = 0; i < amounts.length; i++) {
							if (contentCopy[i] == null) { continue; }
							amounts[i] = contentCopy[i].getAmount();
						}
						for (int i = 0; i < inventory.getSize(); i++) {
							for (Recipe recipe : Bukkit.getRecipesFor(itemFrame.getItem())) {
								List<ItemStack> ingredients = new ArrayList<>();
								if (recipe instanceof ShapedRecipe) {
									ingredients = new ArrayList<>(((ShapedRecipe) recipe).getIngredientMap().values());
								}
								if (recipe instanceof ShapelessRecipe) {
									ingredients = new ArrayList<>(((ShapelessRecipe) recipe).getIngredientList());
								}
								if (ingredients.isEmpty()) { continue; }
								int matchCounter = 0;
								for (ItemStack ingredient : ingredients) {
									if (ingredient == null) {
										// Usually an air slot, so just count it as a match
										matchCounter++;
										continue;
									}
									int first = first(inventory, ingredient.getType(), ingredient.getData(), ingredient.getAmount(), amounts);
									if (first >= 0) {
										if (amounts[first] >= ingredient.getAmount()) {
											matchCounter++;
											amounts[first] -= ingredient.getAmount();
										}
									}
								}
								if (matchCounter < ingredients.size()) { continue; }
								for (ItemStack ingredient : ingredients) {
									if (ingredient == null) {
										continue;
									}
									removeAmount(inventory, ingredient, ingredient.getAmount());
								}
								Item item = itemFrame.getWorld().dropItemNaturally(dropLocation, recipe.getResult());
								item.setPickupDelay(0);

								giveCounter += recipe.getResult().getAmount();
								if (giveCounter >= toGive) {
									event.getPlayer().playSound(dropLocation, Sound.BLOCK_ANVIL_USE, 0.1f, 0.9f);
									event.getPlayer().playSound(dropLocation, Sound.ENTITY_PLAYER_LEVELUP, 0.3f, 0.6f);
									itemFrame.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, particleLocation, 10, 0.2, 0.2, 0.2);
									return;
								}
							}
						}

						// Nothing dropped
						event.getPlayer().playSound(dropLocation, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.1f, 0.2f);
						event.getPlayer().playSound(dropLocation, Sound.BLOCK_METAL_HIT, 0.9f, 0.3f);
						itemFrame.getWorld().spawnParticle(Particle.TOWN_AURA, particleLocation, 10, 0.1, 0.1, 0.1, 0.2);
					}
				}
			}
		}
	}

	public void removeAmount(Inventory inventory, ItemStack itemStack, int amount) {
		for (int i = 0; i < inventory.getSize(); i++) {
			ItemStack slotItem = inventory.getItem(i);
			if (slotItem != null && itemStack.getType() == slotItem.getType() && (itemStack.getData().equals(slotItem.getData()) || itemStack.getData().getData() == -1)) {
				if (amount >= slotItem.getAmount()) {
					amount -= slotItem.getAmount();
					inventory.setItem(i, null);
				} else {
					int tempAmount = slotItem.getAmount();
					slotItem.setAmount(slotItem.getAmount() - amount);
					amount -= tempAmount;
				}
			}
			if (amount <= 0) { break; }
		}
	}

	public int first(Inventory inventory, Material material, MaterialData data, int minAmount, int[] altAmounts) {
		for (int i = 0; i < inventory.getSize(); i++) {
			ItemStack itemStack = inventory.getItem(i);
			if (itemStack != null && itemStack.getType() == material && (itemStack.getData().equals(data) || data.getData() == -1) && (itemStack.getAmount() >= minAmount && (altAmounts != null && altAmounts[i] >= minAmount))) {
				return i;
			}
		}
		return -1;
	}

	Inventory getAttachedInventory(ItemFrame itemFrame) {
		Block attachedTo = itemFrame.getLocation().getBlock().getRelative(itemFrame.getAttachedFace());
		if (attachedTo.getState() instanceof InventoryHolder) {
			return ((InventoryHolder) attachedTo.getState()).getInventory();
		}
		return null;
	}

	JsonObject loadData(Location location) {
		File file = getDataFile(location);
		if (file == null || !file.exists()) { return null; }
		try (Reader reader = new FileReader(file)) {
			return new JsonParser().parse(reader).getAsJsonObject();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	void saveData(ItemFrame itemFrame) {
		File file = getDataFile(itemFrame.getLocation());
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		try (Writer writer = new FileWriter(file)) {
			new Gson().toJson(JsonBuilder.object()
					.name("uuid").value(itemFrame.getUniqueId().toString())
					.name("location").beginObject()
					/**/.name("world").value(itemFrame.getLocation().getWorld().getName())
					/**/.name("x").value(itemFrame.getLocation().getBlockX())
					/**/.name("y").value(itemFrame.getLocation().getBlockY())
					/**/.name("z").value(itemFrame.getLocation().getBlockZ())
					.endObject().buildObject(), writer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	File getDataFile(Location location) {
		String name = location.getWorld().getName() + "-" + location.getBlockX() + "-" + location.getBlockY() + "-" + location.getBlockZ();
		return new File(dataFolder, name);
	}

}
