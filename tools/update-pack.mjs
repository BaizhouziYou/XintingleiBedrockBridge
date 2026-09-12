import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.argv[2]);
const atlasPath = path.join(root, 'textures', 'item_texture.json');
const atlas = JSON.parse(fs.readFileSync(atlasPath, 'utf8'));
atlas.texture_data ??= {};
const drinkDir = path.join(root, 'textures', 'item', 'drinks');
for (const file of fs.readdirSync(drinkDir).filter(file => file.endsWith('.png')).sort()) {
  const id = file.slice(0, -4);
  atlas.texture_data[`xintinglei_drinks_${id}`] = {
    textures: `textures/item/drinks/${id}`
  };
}
fs.writeFileSync(atlasPath, JSON.stringify(atlas) + '\n');
