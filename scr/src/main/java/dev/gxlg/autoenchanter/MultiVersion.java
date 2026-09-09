package dev.gxlg.autoenchanter;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public class MultiVersion {
    private static final Map<Set<RegistryEntry<Enchantment>>, Boolean> cacheCombine = new HashMap<>();

    public static boolean canCombine(RegistryEntry<Enchantment> a, RegistryEntry<Enchantment> b) {
        Set<RegistryEntry<Enchantment>> pair = Set.of(a, b);
        if (cacheCombine.containsKey(pair)) return cacheCombine.get(pair);

        Class<?> re = RegistryEntry.class;
        boolean res;
        if (Reflection.version(">= 1.21")) {
            res = (Boolean) Reflection.wrap("[net.minecraft.class_1887/net.minecraft.enchantment.Enchantment]:null method_60033/canBeCombined re:a re:b");
        } else {
            Class<?> e = Enchantment.class;
            res = (Boolean) Reflection.wrap("e:a.value() method_8188/canCombine e:b.value()");
        }
        cacheCombine.put(pair, res);
        return res;
    }
}
