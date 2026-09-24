package com.vortex.vpn.sub;

import java.util.Locale;

/**
 * Location detection for server names: emoji flags, ISO codes, English and Russian
 * country names. Used to group subscriptions into locations.
 */
public final class Geo {

    private static final String[] DATA = {
            "AD|andorra|андорра",
            "AE|dubai|emirates|uae|оаэ|дубай|эмираты",
            "AL|albania|албания",
            "AM|armenia|армения|ереван",
            "AR|argentina|аргентина",
            "AT|austria|австрия|вена",
            "AU|australia|австралия|сидней|sydney",
            "AZ|azerbaijan|азербайджан|баку",
            "BE|belgium|бельгия",
            "BG|bulgaria|болгария|софия",
            "BR|brazil|бразилия|сан-паулу",
            "BY|belarus|беларусь|минск",
            "CA|canada|канада|торонто|montreal|монреаль",
            "CH|switzerland|швейцария|цюрих|zurich|geneva",
            "CL|chile|чили",
            "CN|china|китай|шанхай|shanghai|beijing",
            "CO|colombia|колумбия",
            "CY|cyprus|кипр",
            "CZ|czech|чехия|прага|prague",
            "DE|germany|deutschland|германия|немецкий|франкфурт|frankfurt|берлин|berlin",
            "DK|denmark|дания|копенгаген",
            "EE|estonia|эстония|таллин",
            "EG|egypt|египет",
            "ES|spain|испания|мадрид|madrid|барселона",
            "FI|finland|финляндия|хельсинки|helsinki",
            "FR|france|франция|париж|paris|марсель",
            "GB|united kingdom|england|britain|великобритания|англия|лондон|london",
            "GE|georgia|грузия|тбилиси",
            "GR|greece|греция|афины",
            "HK|hong kong|hongkong|гонконг|香港",
            "HR|croatia|хорватия",
            "HU|hungary|венгрия|будапешт",
            "ID|indonesia|индонезия|джакарта",
            "IE|ireland|ирландия|дублин|dublin",
            "IL|israel|израиль|тель-авив",
            "IN|india|индия|мумбаи|mumbai",
            "IR|iran|иран|тегеран",
            "IS|iceland|исландия|рейкьявик",
            "IT|italy|италия|милан|milan|рим|rome",
            "JP|japan|япония|токио|tokyo|osaka|осака",
            "KR|korea|south korea|корея|сеул|seoul",
            "KZ|kazakhstan|казахстан|алматы|almaty|астана",
            "LT|lithuania|литва|вильнюс",
            "LU|luxembourg|люксембург",
            "LV|latvia|латвия|рига",
            "MD|moldova|молдова|кишинев",
            "MX|mexico|мексика",
            "MY|malaysia|малайзия|куала",
            "NG|nigeria|нигерия",
            "NL|netherlands|holland|нидерланды|голландия|амстердам|amsterdam",
            "NO|norway|норвегия|осло",
            "NZ|new zealand|новая зеландия",
            "PH|philippines|филиппины|манила",
            "PK|pakistan|пакистан",
            "PL|poland|польша|варшава|warsaw",
            "PT|portugal|португалия|лиссабон",
            "RO|romania|румыния|бухарест",
            "RS|serbia|сербия|белград",
            "RU|russia|russian|россия|москва|moscow|спб|saint petersburg|питер|новосибирск|екатеринбург",
            "SA|saudi|саудовская",
            "SE|sweden|швеция|стокгольм|stockholm",
            "SG|singapore|сингапур",
            "SI|slovenia|словения",
            "SK|slovakia|словакия",
            "TH|thailand|таиланд|бангкок|bangkok",
            "TR|turkey|turkiye|турция|стамбул|istanbul",
            "TW|taiwan|тайвань|тайбэй",
            "UA|ukraine|украина|киев|kyiv|kiev",
            "US|united states|usa|america|сша|америка|нью-йорк|new york|лос-анджелес|los angeles|сиэтл|seattle|dallas|чикаго|chicago|майами|miami|phoenix|ashburn",
            "UZ|uzbekistan|узбекистан|ташкент",
            "VN|vietnam|вьетнам|ханой",
            "ZA|south africa|юар|южная африка",
    };

    private Geo() {
    }

    /** ISO-3166 alpha-2 code detected from a server name, or null. */
    public static String countryCode(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String fromFlag = fromFlag(name);
        if (fromFlag != null) {
            return fromFlag;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // Direct token match of an ISO code, e.g. "NL #1", "[DE] Frankfurt".
        String cleaned = lower.replaceAll("[^a-z\\s\\-]", " ");
        for (String token : cleaned.split("\\s+")) {
            if (token.length() == 2 && isKnown(token)) {
                return token.toUpperCase(Locale.ROOT);
            }
        }
        for (String row : DATA) {
            int split = row.indexOf('|');
            String code = row.substring(0, split);
            for (String alias : row.substring(split + 1).split("\\|")) {
                if (!alias.isEmpty() && lower.contains(alias)) {
                    return code;
                }
            }
        }
        return null;
    }

    private static boolean isKnown(String code) {
        for (String row : DATA) {
            if (row.startsWith(code + "|")) {
                return true;
            }
        }
        return false;
    }

    public static String fromFlag(String name) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i + 1 < name.length(); i++) {
            char c1 = name.charAt(i);
            char c2 = name.charAt(i + 1);
            if (c1 >= 0x1F1E6 && c1 <= 0x1F1FF && c2 >= 0x1F1E6 && c2 <= 0x1F1FF) {
                code.append((char) ('A' + (c1 - 0x1F1E6)));
                code.append((char) ('A' + (c2 - 0x1F1E6)));
                i++;
            }
        }
        return code.length() == 2 ? code.toString() : null;
    }

    /** Flag emoji for an ISO code, or a globe when unknown. */
    public static String flag(String code) {
        if (code == null || code.length() != 2) {
            return "\uD83C\uDF10";
        }
        String upper = code.toUpperCase(Locale.ROOT);
        int first = 0x1F1E6 + (upper.charAt(0) - 'A');
        int second = 0x1F1E6 + (upper.charAt(1) - 'A');
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    public static String countryName(String code) {
        if (code == null) {
            return "";
        }
        for (String row : DATA) {
            if (row.startsWith(code + "|")) {
                int split = row.indexOf('|');
                String[] aliases = row.substring(split + 1).split("\\|");
                for (String alias : aliases) {
                    if (alias.indexOf(' ') < 0 && isLatin(alias)) {
                        return capitalize(alias);
                    }
                }
                return code;
            }
        }
        return code;
    }

    private static boolean isLatin(String value) {
        for (char c : value.toCharArray()) {
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
