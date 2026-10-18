package com.crystalai.plugin.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import javax.annotation.Nullable;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A fighting style the body plays 20 times a second: the model writes it during training (/yzmac egitim),
 * the body plays it in every fight move afterwards. Every value is clamped to its range, so a model can
 * choose anything inside the same box as the other one.
 */
record FightCard(
        double charge,        // vurus_sarj: swing only at this attack charge (0.5 spam .. 1.0 full)
        double crit,          // kritik: chance to jump for a falling crit with a charged swing
        int wTap,             // wtap: ticks without W after a hit (resets sprint: next hit knocks back)
        double strafe,        // strafe: how much it circles instead of walking straight in
        int strafeSwitch,     // strafe_degistir: ticks before circling the other way
        int backOff,          // geri_cekil: ticks spent stepping back after a hit
        double shield,        // kalkan: chance to raise the shield when the opponent's swing is ready
        double eatAt,         // iyiles_can: eat a golden apple at or below this health
        double reach,         // menzil: distance at which it swings
        boolean sprint) {     // sprint: run in

    /** Crit chance the body plays: trained cards never go below MIN_CRIT, the untrained body stays as it was. */
    double playedCrit() {
        return this.equals(UNTRAINED) ? crit : Math.max(crit, MIN_CRIT);
    }

    /** Shield chance the body plays: trained cards never go below MIN_SHIELD (playtests showed the bots never held it). */
    double playedShield() {
        return this.equals(UNTRAINED) ? shield : Math.max(shield, MIN_SHIELD);
    }

    /** The plain body before any training, as it fought before cards: 0.9 charge (config sword-min-charge), straight in. */
    static final FightCard UNTRAINED = new FightCard(0.9, 0.0, 0, 0.0, 12, 0, 0.0, 6.0, 2.6, true);

    /** Same ceiling for both models: past these, circling and crit jumps cost more hits than they save. */
    static final double MAX_STRAFE = 0.5;
    static final double MAX_CRIT = 0.5;
    /** Flat-ground swinging gets blocked and out-traded: some crit jumps are not optional. */
    static final double MIN_CRIT = 0.25;
    /** Training pulled the shield to 0 for both models; a human player raised theirs about 0.36 of the time. */
    static final double MIN_SHIELD = 0.35;

    FightCard {
        charge = clamp(charge, 0.5, 1.0);
        crit = clamp(crit, 0.0, MAX_CRIT);
        wTap = (int) clamp(wTap, 0, 6);
        strafe = clamp(strafe, 0.0, MAX_STRAFE);
        strafeSwitch = (int) clamp(strafeSwitch, 4, 30);
        backOff = (int) clamp(backOff, 0, 10);
        shield = clamp(shield, 0.0, 1.0);
        eatAt = clamp(eatAt, 0.0, 14.0);
        reach = clamp(reach, 2.2, 3.0);
    }

    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("vurus_sarj", round(charge));
        json.addProperty("kritik", round(crit));
        json.addProperty("wtap", wTap);
        json.addProperty("strafe", round(strafe));
        json.addProperty("strafe_degistir", strafeSwitch);
        json.addProperty("geri_cekil", backOff);
        json.addProperty("kalkan", round(shield));
        json.addProperty("iyiles_can", round(eatAt));
        json.addProperty("menzil", round(reach));
        json.addProperty("sprint", sprint);
        return json;
    }

    /** Missing or broken fields keep {@code base}'s value. */
    static FightCard fromJson(@Nullable JsonObject j, FightCard base) {
        if (j == null) {
            return base;
        }
        return new FightCard(
                num(j, "vurus_sarj", base.charge), num(j, "kritik", base.crit), (int) Math.round(num(j, "wtap", base.wTap)),
                num(j, "strafe", base.strafe), (int) Math.round(num(j, "strafe_degistir", base.strafeSwitch)),
                (int) Math.round(num(j, "geri_cekil", base.backOff)), num(j, "kalkan", base.shield),
                num(j, "iyiles_can", base.eatAt), num(j, "menzil", base.reach),
                j.has("sprint") ? bool(j, "sprint", base.sprint) : base.sprint);
    }

    static @Nullable FightCard parse(@Nullable String raw, FightCard base) {
        if (raw == null) {
            return null;
        }
        try {
            return fromJson(JsonParser.parseString(raw).getAsJsonObject(), base);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static FightCard fromConfig(@Nullable ConfigurationSection s, FightCard base) {
        if (s == null) {
            return base;
        }
        return new FightCard(s.getDouble("vurus_sarj", base.charge), s.getDouble("kritik", base.crit),
                s.getInt("wtap", base.wTap), s.getDouble("strafe", base.strafe),
                s.getInt("strafe_degistir", base.strafeSwitch), s.getInt("geri_cekil", base.backOff),
                s.getDouble("kalkan", base.shield), s.getDouble("iyiles_can", base.eatAt),
                s.getDouble("menzil", base.reach), s.getBoolean("sprint", base.sprint));
    }

    /** What each value does, for the coach prompt. Same text for both models. */
    static String explain() {
        return """
                vurus_sarj (0.5-1.0): kılıç bu şarja gelince vurur. 0.5 = sürekli az hasarlı vuruş, 1.0 = tam hasar ama daha seyrek.
                kritik (0.25-0.5): şarj doluyken zıplayıp düşerken vurma (kritik, %50 fazla hasar) olasılığı. Zıplarken koşu yok, daha yavaş.
                wtap (0-6 tick): vuruştan sonra bu kadar tick ileri tuşunu bırakır; koşu sıfırlanır, sonraki vuruş rakibi geri savurur.
                strafe (0-0.5): rakibe dümdüz değil çapraz/yandan yaklaşma ve yakında yana kayma miktarı.
                strafe_degistir (4-30 tick): yana kayma yönünü bu kadar tickte bir değiştirir.
                geri_cekil (0-10 tick): vuruştan sonra bu kadar tick geri adım atar (vur-kaç).
                kalkan (0-1): rakibin kılıcı doluyken ve yakındayken kalkan kaldırma olasılığı. Kalkan açıkken vuramazsın.
                iyiles_can (0-14): can bu değerin altına inince altın elma yer (elma sınırlı).
                menzil (2.2-3.0 blok): vuruşa başladığı mesafe. 3.0 en uzak erişim.
                sprint (true/false): koşarak yaklaşma.""";
    }

    String summary() {
        return String.format(Locale.ROOT, "şarj %.2f · kritik %.2f · wtap %d · strafe %.2f/%d · geri %d · kalkan %.2f · elma<%.1f · menzil %.2f%s",
                charge, crit, wTap, strafe, strafeSwitch, backOff, shield, eatAt, reach, sprint ? " · koşu" : "");
    }

    private static double num(JsonObject j, String key, double fallback) {
        try {
            return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject j, String key, boolean fallback) {
        try {
            return j.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
