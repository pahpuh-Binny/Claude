/*
 * Pokopia Companion Guide: reference data.
 *
 * Compiled from community guides (Serebii, Nintendo Life, Dexerto, GameSpot,
 * Pocket Tactics, Bulbapedia and others) as of November 2026.
 * Where guides disagree, the entry carries `verify: true` and a `note`
 * explaining the conflict, and the app shows a "Check in-game" badge.
 *
 * Everything here is plain data, so it is easy to correct or extend:
 * add a Pokémon, habitat or move by appending an object to its list.
 */
window.POKOPIA_DATA = {
  meta: {
    game: 'Pokémon Pokopia',
    platform: 'Nintendo Switch 2',
    released: 'March 5, 2026',
    updated: 'November 2026',
    habitatTotal: 209,
    pokedexTotal: 'about 300'
  },

  /* Story areas, in play order. `step` drives the route map. */
  areas: [
    {
      id: 'withered-wasteland',
      name: 'Withered Wasteland',
      step: 1,
      inspired: 'Fuchsia City',
      summary: 'Where the story starts. The land is dry and dusty, and almost no Pokémon live here until you start restoring it.',
      goals: [
        'Meet Professor Tangrowth, who asks you to help rebuild',
        'Build your first habitat: four tufts of Tall Grass',
        'Learn Water Gun from Squirtle and Leafage from Bulbasaur',
        'Throw a Rain Dance party to bring the rain back'
      ],
      unlocks: 'Bleak Beach and Rocky Ridges'
    },
    {
      id: 'bleak-beach',
      name: 'Bleak Beach',
      step: 2,
      inspired: 'Vermilion City',
      summary: 'A coastal area. You can take it on before or after Rocky Ridges.',
      goals: [
        'Learn Surf from Lapras',
        'Try a Seaside Tall Grass habitat (Tall Grass ×4 + Ocean Water ×2)'
      ],
      unlocks: 'Counts toward opening Sparkling Skylands'
    },
    {
      id: 'rocky-ridges',
      name: 'Rocky Ridges',
      step: 2,
      inspired: 'Pewter City and Mt. Moon',
      summary: 'Mountain country with two map layers: the Surface and the Underground.',
      goals: [
        'Explore both the Surface and the Underground layers',
        'Look for Chef Dente, who some guides say starts cooking here'
      ],
      unlocks: 'Counts toward opening Sparkling Skylands'
    },
    {
      id: 'sparkling-skylands',
      name: 'Sparkling Skylands',
      step: 3,
      inspired: 'Celadon City and Saffron City',
      summary: 'The last area of the main story. It opens once the main stories of Bleak Beach and Rocky Ridges are both complete.',
      goals: ['Learn Glide from Dragonite'],
      unlocks: 'The ending'
    },
    {
      id: 'post-game',
      name: 'After the credits',
      step: 4,
      inspired: null,
      summary: 'Post-game content. Guides also mention Dream Island locations such as Cloud Island and Bubbly Basin.',
      goals: ['Learn Magnet Rise from Magnemite'],
      unlocks: null,
      verify: true,
      note: 'Post-game locations are named differently across guides.'
    },
    {
      id: 'palette-town',
      name: 'Palette Town',
      step: 0,
      inspired: 'Pallet Town',
      summary: 'A sandbox town just off Withered Wasteland, for building freely outside the story.',
      goals: [],
      unlocks: null
    }
  ],

  /* Ditto's transformation moves, roughly in the order you learn them. */
  moves: [
    {
      id: 'water-gun', name: 'Water Gun', from: 'Squirtle', area: 'withered-wasteland',
      use: 'Spray water to bring dry ground and plants back to life.',
      meal: 'Soup'
    },
    {
      id: 'leafage', name: 'Leafage', from: 'Bulbasaur', area: 'withered-wasteland',
      use: 'Grow tall grass on tiles. Powered up, it also grows moss on rock blocks and duckweed on water.',
      meal: 'Salad',
      note: 'A few guides call this move Razor Leaf.'
    },
    {
      id: 'cut', name: 'Cut', from: 'Scyther', area: null,
      use: 'Cut down trees and plants for building materials.',
      meal: 'Bread'
    },
    {
      id: 'rock-smash', name: 'Rock Smash', from: 'Hitmonchan', area: null,
      use: 'Break rocks apart to collect stone.',
      meal: 'Hamburger Steak'
    },
    {
      id: 'rototiller', name: 'Rototiller', from: 'Drilbur', area: null,
      use: 'Till the soil so you can plant in it.',
      verify: true,
      note: 'Some guides call this move Cultivate.'
    },
    {
      id: 'rollout', name: 'Rollout', from: 'Graveler', area: null,
      use: 'Roll along the ground to get around quickly.',
      verify: true,
      note: 'Most guides say Graveler teaches it; one says Drilbur.'
    },
    {
      id: 'jump', name: 'Jump', from: 'Magikarp', area: null,
      use: 'Hop up onto higher ground.',
      verify: true,
      note: 'One guide names the Magikarp move Splash instead.'
    },
    {
      id: 'surf', name: 'Surf', from: 'Lapras', area: 'bleak-beach',
      use: 'Travel across water.',
      verify: true,
      note: 'One guide says Surf comes from a request to bring crafted Toy Blocks to Zorua.'
    },
    {
      id: 'glide', name: 'Glide', from: 'Dragonite', area: 'sparkling-skylands',
      use: 'Glide through the air from high places.'
    },
    {
      id: 'magnet-rise', name: 'Magnet Rise', from: 'Magnemite', area: 'post-game',
      use: 'A post-game move, learned after the main story.'
    }
  ],

  /* Pokémon specialties: what a resident Pokémon does for your town. */
  specialties: [
    { id: 'grow', name: 'Grow', does: 'Makes plants grow faster: crops, flowers and trees.', examples: ['Bulbasaur'] },
    { id: 'burn', name: 'Burn', does: 'Sets fire to flammable objects, such as a campfire.', examples: ['Charmander'] },
    { id: 'water', name: 'Water', does: 'Helps with watering. Guides have not confirmed the exact effect yet.', examples: ['Squirtle'], verify: true },
    { id: 'chop', name: 'Chop', does: 'Processes small logs into lumber.', examples: ['Scyther'] },
    { id: 'crush', name: 'Crush', does: 'Mashes materials into new ones, like paint from food or cement from limestone in a concrete mixer.', examples: [] },
    { id: 'build', name: 'Build', does: 'Leads your building projects.', examples: [] },
    { id: 'bulldoze', name: 'Bulldoze', does: 'Leads demolition, rebuilding and relocation projects.', examples: [] },
    { id: 'litter', name: 'Litter', does: 'Drops useful materials near its home.', examples: [] },
    { id: 'gather', name: 'Gather', does: 'Picks up items on the ground and stores them in a community box.', examples: ['Drifblim', 'Mismagius', 'Vespiquen'] },
    { id: 'search', name: 'Search', does: 'Helps you find buried items.', examples: ['Drilbur', 'Ekans', 'Growlithe', 'Psyduck', 'Zubat', 'Dreepy'] },
    { id: 'fly', name: 'Fly', does: 'Carries you to a Pokémon you are looking for.', examples: ['Pidgey', 'Pidgeotto', 'Pidgeot', 'Hoothoot', 'Noctowl'] },
    { id: 'teleport', name: 'Teleport', does: 'Instantly takes you to a Pokémon you are searching for.', examples: ['Kirlia'] },
    { id: 'yawn', name: 'Yawn', does: 'Tells you how humid the area is.', examples: ['Slowpoke', 'Slowbro'] },
    { id: 'trade', name: 'Trade', does: 'Swaps items with you at a cash register.', examples: ['Hitmonlee', 'Hitmonchan', 'Hitmontop', 'Slowbro', 'Hoothoot', 'Noctowl'] },
    { id: 'recycle', name: 'Recycle', does: 'Turns nonburnable garbage into iron ore.', examples: [] },
    { id: 'hype', name: 'Hype', does: 'Dances when it hears music, which lifts everyone’s mood.', examples: ['Jigglypuff', 'Clefairy', 'Chatot', 'Azurill', 'Diglett', 'Raichu', 'Cinderace'] },
    { id: 'generate', name: 'Generate', does: 'Powers machines with electricity.', examples: ['Pikachu', 'Mareep', 'Flaaffy', 'Ampharos', 'Magnemite', 'Magneton', 'Rotom', 'Elekid', 'Electabuzz'] },
    { id: 'paint', name: 'Paint', does: 'Paints buildings so you can customize their look.', examples: ['Smeargle', 'Grafaiai'] },
    { id: 'explode', name: 'Explode', does: 'Guides have not described this one in detail yet.', examples: ['Voltorb'], verify: true }
  ],

  /*
   * Pokémon covered by this guide. `dex` is the National Pokédex number,
   * used for sorting. Pokopia originals have dex: null.
   * `specs` are specialty ids from the list above.
   */
  pokemon: [
    { name: 'Bulbasaur', dex: 1, specs: ['grow'], where: 'Tall Grass habitat', role: 'Teaches Ditto Leafage' },
    { name: 'Charmander', dex: 4, specs: ['burn'], where: 'Tall Grass; a Campsite on sunny or cloudy days' },
    { name: 'Charizard', dex: 6, specs: [], where: 'Tall Grass (one guide)', verify: true },
    { name: 'Squirtle', dex: 7, specs: ['water'], where: 'Tall Grass, Hydrated Tall Grass', role: 'Teaches Ditto Water Gun' },
    { name: 'Blastoise', dex: 9, specs: [], where: 'Hydrated Tall Grass' },
    { name: 'Pidgey', dex: 16, specs: ['fly'] },
    { name: 'Pidgeotto', dex: 17, specs: ['fly'] },
    { name: 'Pidgeot', dex: 18, specs: ['fly'] },
    { name: 'Ekans', dex: 23, specs: ['search'] },
    { name: 'Pikachu', dex: 25, specs: ['generate'] },
    { name: 'Raichu', dex: 26, specs: ['hype'] },
    { name: 'Clefairy', dex: 35, specs: ['hype'] },
    { name: 'Jigglypuff', dex: 39, specs: ['hype'] },
    { name: 'Zubat', dex: 41, specs: ['search'] },
    { name: 'Oddish', dex: 43, specs: [], where: 'Tall Grass (one guide)', verify: true },
    { name: 'Diglett', dex: 50, specs: ['hype'] },
    { name: 'Psyduck', dex: 54, specs: ['search'] },
    { name: 'Growlithe', dex: 58, specs: ['search'] },
    { name: 'Geodude', dex: 74, specs: [], where: 'Tall Grass (one guide)', verify: true },
    { name: 'Graveler', dex: 75, specs: [], role: 'Teaches Ditto Rollout (most guides)' },
    { name: 'Slowpoke', dex: 79, specs: ['yawn'] },
    { name: 'Slowbro', dex: 80, specs: ['yawn', 'trade'] },
    { name: 'Magnemite', dex: 81, specs: ['generate'], role: 'Teaches Ditto Magnet Rise after the story' },
    { name: 'Magneton', dex: 82, specs: ['generate'] },
    { name: 'Voltorb', dex: 100, specs: ['explode'] },
    { name: 'Hitmonlee', dex: 106, specs: ['trade'] },
    { name: 'Hitmonchan', dex: 107, specs: ['trade'], role: 'Teaches Ditto Rock Smash' },
    { name: 'Chansey', dex: 113, specs: [], where: 'Pokémon Center', role: 'Gives the quest “A Warm Welcome”' },
    { name: 'Scyther', dex: 123, specs: ['chop'], role: 'Teaches Ditto Cut' },
    { name: 'Electabuzz', dex: 125, specs: ['generate'] },
    { name: 'Magikarp', dex: 129, specs: [], role: 'Teaches Ditto Jump' },
    { name: 'Lapras', dex: 131, specs: [], where: 'Bleak Beach', role: 'Teaches Ditto Surf' },
    { name: 'Dragonite', dex: 149, specs: [], where: 'Sparkling Skylands', role: 'Teaches Ditto Glide' },
    { name: 'Hoothoot', dex: 163, specs: ['fly', 'trade'] },
    { name: 'Noctowl', dex: 164, specs: ['fly', 'trade'] },
    { name: 'Mareep', dex: 179, specs: ['generate'] },
    { name: 'Flaaffy', dex: 180, specs: ['generate'] },
    { name: 'Ampharos', dex: 181, specs: ['generate'] },
    { name: 'Smeargle', dex: 235, specs: ['paint'] },
    { name: 'Hitmontop', dex: 237, specs: ['trade'] },
    { name: 'Elekid', dex: 239, specs: ['generate'] },
    { name: 'Kirlia', dex: 281, specs: ['teleport'] },
    { name: 'Azurill', dex: 298, specs: ['hype'] },
    { name: 'Vespiquen', dex: 416, specs: ['gather'], note: 'Gathers honey' },
    { name: 'Drifblim', dex: 426, specs: ['gather'] },
    { name: 'Mismagius', dex: 429, specs: ['gather'] },
    { name: 'Chatot', dex: 441, specs: ['hype'] },
    { name: 'Rotom', dex: 479, specs: ['generate'] },
    { name: 'Drilbur', dex: 529, specs: ['search'], role: 'Teaches Ditto Rototiller' },
    { name: 'Zorua', dex: 570, specs: [], role: 'Wants crafted Toy Blocks (one guide links this to Surf)', verify: true },
    { name: 'Cinderace', dex: 815, specs: ['hype'] },
    { name: 'Dreepy', dex: 885, specs: ['search'] },
    { name: 'Grafaiai', dex: 945, specs: ['paint'] },
    { name: 'Professor Tangrowth', dex: null, specs: [], where: 'Withered Wasteland', role: 'Your guide. Studies the land and why its people and Pokémon vanished' },
    { name: 'Peakychu', dex: null, specs: [], note: 'A pale Pikachu with drooping ears' },
    { name: 'Mosslax', dex: null, specs: [], note: 'A Snorlax covered in moss and topped with a flower' },
    { name: 'Smearguru', dex: null, specs: [], role: 'Its first quest unlocks the Bench with Greenery' }
  ],

  /*
   * Habitats: confirmed early builds. `req` items are [material, count].
   * The in-game Habitat Dex has 209 entries; add the rest in the app.
   */
  habitats: [
    {
      id: 'h001', no: 1, name: 'Tall Grass',
      req: [['Tall Grass', 4]],
      residents: ['Bulbasaur', 'Charmander', 'Squirtle'],
      cond: 'The easiest first habitat. One guide also lists Geodude, Oddish and Charizard here.'
    },
    {
      id: 'h002', no: 2, name: 'Tree-Shaded Tall Grass',
      req: [['Large Tree', 1], ['Tall Grass', 4]],
      residents: []
    },
    {
      id: 'h004', no: 4, name: 'Hydrated Tall Grass',
      req: [['Tall Grass', 4], ['Water', 2]],
      residents: ['Squirtle', 'Blastoise']
    },
    {
      id: 'h005', no: 5, name: 'Seaside Tall Grass',
      req: [['Tall Grass', 4], ['Ocean Water', 2]],
      residents: [],
      cond: 'Build it on the coast, for example at Bleak Beach.'
    },
    {
      id: 'hydrated-flower-bed', no: null, name: 'Hydrated Flower Bed',
      req: [['Wildflowers', 4], ['Water', 2]],
      residents: [],
      cond: 'Place the four wildflowers right beside the water.'
    },
    {
      id: 'campsite', no: null, name: 'Campsite',
      req: [['Lit Campfire', 1], ['Straw Table', 1], ['Straw Stool', 1]],
      residents: ['Charmander'],
      cond: 'Charmander only visits on sunny or cloudy days, never in rain.',
      verify: true,
      note: 'Reported by one guide only.'
    },
    {
      id: 'bench-with-greenery', no: null, name: 'Bench with Greenery',
      req: [['Hedge', 2], ['Wide Seat', 1]],
      residents: ['Bulbasaur'],
      cond: 'Unlocked through Smearguru’s first quest.',
      verify: true,
      note: 'Hedge count not confirmed.'
    }
  ],

  /* Meals that power up Ditto's moves. */
  meals: [
    { meal: 'Salad', tool: 'Chopping Board', heat: false, move: 'Leafage', starter: 'Simple Salad' },
    { meal: 'Soup', tool: 'Cooking Pot', heat: true, move: 'Water Gun', starter: 'Simple Soup' },
    { meal: 'Bread', tool: 'Frying Pan', heat: true, move: 'Cut', starter: 'Simple Bread' },
    { meal: 'Hamburger Steak', tool: 'Frying Pan', heat: true, move: 'Rock Smash', starter: 'Simple Hamburger Steak' }
  ],

  cookingNotes: [
    'Eating a meal fully restores the PP of your other moves and powers up its matching move.',
    'Cooking tools are crafted at the workbench. Most need a heat source, so put a Campfire next to your kitchen.',
    'Some recipes need a Pokémon with a certain specialty to help. Recipes without one are ready as soon as cooking unlocks.',
    'Guides list 24 recipes split evenly across the four meal types.'
  ],

  cookingUnlock: {
    verify: true,
    note: 'Guides disagree on how cooking unlocks. One says Chansey gives you the Basic Cooking Kit blueprint after the Pokémon Center quest “A Warm Welcome.” Another says Chef Dente in Rocky Ridges starts it.'
  },

  powerUpNote: 'Guides disagree on whether meal power-ups last for a while or forever. Test it on your save.',

  crafting: [
    'Your first workbench costs 2 Stones.',
    'Put a storage box right next to the workbench so you can craft straight from stored materials.',
    'There are hundreds of crafting recipes (600+). New ones arrive as blueprints from quests and requests.'
  ],

  multiplayer: [
    'Up to four players can build together.',
    'Recipes you unlock in single player stay unlocked in multiplayer.',
    'Visitors can use your workbench, but they only see the recipes they have unlocked themselves.'
  ],

  tips: [
    'Start with a Tall Grass habitat: four tufts of tall grass bunched together in one plot. It is the easiest build in the game.',
    'Build more than one copy of a habitat. Extra copies raise your odds of finding the Pokémon that live there.',
    'Some habitats only attract Pokémon at certain times of day, in certain weather, or once your Environment Level is high enough. Check back later.',
    'Switch the camera to “Far” in the settings. It makes building much easier.',
    'Resting at a Pokémon Center restores your PP for free.',
    'Pokémon with an orange speech bubble have a request for you. Requests are how you unlock most of Ditto’s moves.',
    'Ditto keeps every move it learns, so you never have to relearn one.',
    'Cook one Simple Soup, Salad, Bread and Hamburger Steak early. Each powers up one of your four core moves.',
    'Put a storage box next to your workbench so you can craft from stored materials.',
    'Charmander will not show up at a Campsite in the rain. Try again on a sunny or cloudy day.'
  ],

  /* Starter entries for the request tracker on first launch. */
  starterRequests: [
    { text: 'Cook one of each basic meal: Soup, Salad, Bread and Hamburger Steak', who: '', area: '' },
    { text: 'Build a second Tall Grass habitat to attract more Pokémon', who: '', area: 'withered-wasteland' }
  ]
};
