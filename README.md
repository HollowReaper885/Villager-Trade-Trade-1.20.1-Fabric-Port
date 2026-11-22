# Enchanted Book Trader — Fabric 1.20.1 port

Right-click a **Librarian** villager while holding an **enchanted book** to teach it a trade
(sells that book for emeralds). Books with several enchantments open a picker GUI.
Teaching the same enchantment again rerolls the price (never higher) and restocks the trade.
Max 5 taught trades per villager.

## Build
Requires JDK 17+.

    ./gradlew build

The jar is written to `build/libs/enchanted-book-trader-1.1.2.jar`.
Needs Fabric Loader >= 0.14.21 and Fabric API for 1.20.1.

## Building on GitHub
Push this folder to a repo. The workflow in `.github/workflows/build.yml` runs on every push;
download the jar from the run's **Artifacts** section (use the one without `-sources`).
