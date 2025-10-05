package com.example;

import com.example.api.ElpriserAPI;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        ElpriserAPI api = new ElpriserAPI();

        var zone = "";
        var date = "";
        var charging = "";
        var sorted = false;
        var help = false;
        if (args.length == 0) {
            System.out.println("Usage: java -jar elpriser.jar --zone <SE1|SE2|SE3|SE4> [--date yyyy-MM-dd][--sorted] [--charging]");
            return;
        }

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--zone") && i + 1 < args.length) {
                zone = args[++i];
            } else if (args[i].equals("--date") && i + 1 < args.length) {
                date = args[++i];
            } else if (args[i].equals("--sorted")) {
                sorted = true;
            } else if (args[i].equals("--charging") && i + 1 < args.length) {
                charging = args[++i];
            } else if (args[i].equals("--help")) {
                help = true;
            }
        }

        if (help) {
            System.out.println("Usage: java -jar elpriser.jar --zone <SE1|SE2|SE3|SE4> [--date yyyy-MM-dd][--sorted] [--charging]");
            return;
        }

        if (zone.isEmpty()) {
            System.out.println("fel: zone required");
            return;
        }

        ElpriserAPI.Prisklass prisklass;
        try {
            prisklass = ElpriserAPI.Prisklass.valueOf(zone);
        } catch (IllegalArgumentException e) {
            System.out.println("fel: ogiltig zon " + zone);
            return;
        }
        LocalDate datum = LocalDate.now();
        if (!date.isEmpty()) {
            try {
                datum = LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                System.out.println("fel: ogiltigt datum " + date);
                return;
            }
        }

        List<ElpriserAPI.Elpris> priser = api.getPriser(datum, prisklass);

        if (priser.isEmpty()) {
            System.out.println("inga priser tillgängliga för " + datum);
            return;
        }

        if (!charging.isEmpty()) {
            int laddningstimmar;
            try {
                laddningstimmar = Integer.parseInt(charging.substring(0, charging.length() - 1));
            } catch (Exception e) {
                System.out.println("Felaktigt värde för " + charging);
                return;
            }

            List<ElpriserAPI.Elpris> laddningspriser = api.getPriser(datum.plusDays(1), prisklass);
            if (laddningspriser != null && !laddningspriser.isEmpty()) {
                priser.addAll(laddningspriser);
            }

            double bästAvg = Double.MAX_VALUE;
            int bästIndex = 0;

            for (int i = 0; i <= priser.size() - laddningstimmar; i++) {
                double sum = 0;
                for (int j = 0; j < laddningstimmar; j++) {
                    sum += priser.get(i + j).sekPerKWh();
                }
                double avg = sum / laddningstimmar;
                if (avg < bästAvg) {
                    bästAvg = avg;
                    bästIndex = i;
                }
            }

            ElpriserAPI.Elpris start = priser.get(bästIndex);

            System.out.println("Påbörja laddning kl " + start.timeStart().toLocalTime());
            System.out.println("Medelpris för fönster: " + formatOre(bästAvg) + " öre");
            return;
        }

        List<ElpriserAPI.Elpris> timpriser = priser;
        if (priser.size() > 24) {
            timpriser = grupperaPrisPerTimme(priser);
        }

        double sum = 0;
        double minPris = Double.MAX_VALUE;
        double maxPris = Double.MIN_VALUE;
        int minIndex = -1;
        int maxIndex = -1;

        for (int i = 0; i < timpriser.size(); i++) {
            double val = timpriser.get(i).sekPerKWh();
            if (val < minPris) {
                minPris = val;
                minIndex = i;
            }
            if (val > maxPris) {
                maxPris = val;
                maxIndex = i;
            }
            sum += val;
        }

        double medelpris = sum / timpriser.size();

        System.out.println("Lägsta pris: " +
                formatTimeRange(timpriser.get(minIndex)) + ", " +
                formatOre(minPris) + " öre");

        System.out.println("Högsta pris: " +
                formatTimeRange(timpriser.get(maxIndex)) + ", " +
                formatOre(maxPris) + " öre");

        System.out.println("Medelpris: " + formatOre(medelpris) + " öre");
        System.out.println();

        List<ElpriserAPI.Elpris> morgondagensPriser = api.getPriser(datum.plusDays(1), prisklass);
        if (morgondagensPriser != null && !morgondagensPriser.isEmpty()) {
            priser.addAll(morgondagensPriser);
        }

        if (sorted) {
            List<ElpriserAPI.Elpris> sortedPriser = new ArrayList<>(priser);
            sorteraPriserFallande(sortedPriser);

            for (int i = 0; i < sortedPriser.size(); i++) {
                ElpriserAPI.Elpris p = sortedPriser.get(i);
                System.out.println(formatTimeRange(p) + " " + formatOre(p.sekPerKWh()) + " öre");
            }
        } else {
            for (int i = 0; i < priser.size(); i++) {
                ElpriserAPI.Elpris p = priser.get(i);
                System.out.println(formatTimeRange(p) + " " + formatOre(p.sekPerKWh()) + " öre");
            }
        }
    }

    private static List<ElpriserAPI.Elpris> grupperaPrisPerTimme(List<ElpriserAPI.Elpris> priser) {
        Map<Integer, List<ElpriserAPI.Elpris>> hourlyGroups = new LinkedHashMap<>();

        for (int i = 0; i < priser.size(); i++) {
            ElpriserAPI.Elpris p = priser.get(i);
            int timme = p.timeStart().getHour();
            
            if (!hourlyGroups.containsKey(timme)) {
                hourlyGroups.put(timme, new ArrayList<>());
            }
            hourlyGroups.get(timme).add(p);
        }

        List<ElpriserAPI.Elpris> timpriser = new ArrayList<>();
        
        for (Map.Entry<Integer, List<ElpriserAPI.Elpris>> entry : hourlyGroups.entrySet()) {
            List<ElpriserAPI.Elpris> hourPrices = entry.getValue();

            double sumPris = 0;
            for (int i = 0; i < hourPrices.size(); i++) {
                sumPris += hourPrices.get(i).sekPerKWh();
            }
            double avgPrice = sumPris / hourPrices.size();

            ElpriserAPI.Elpris first = hourPrices.get(0);
            ElpriserAPI.Elpris last = hourPrices.get(hourPrices.size() - 1);

            timpriser.add(new ElpriserAPI.Elpris(
                    avgPrice,
                    first.eurPerKWh(),
                    first.exr(),
                    first.timeStart(),
                    last.timeEnd()
            ));
        }
        
        return timpriser;
    }

    private static void sorteraPriserFallande(List<ElpriserAPI.Elpris> priser) {
        for (int i = 0; i < priser.size() - 1; i++) {
            for (int j = 0; j < priser.size() - 1 - i; j++) {
                if (priser.get(j).sekPerKWh() < priser.get(j + 1).sekPerKWh()) {
                    // Byt plats
                    ElpriserAPI.Elpris temp = priser.get(j);
                    priser.set(j, priser.get(j + 1));
                    priser.set(j + 1, temp);
                }
            }
        }
    }

    private static String formatTimeRange(ElpriserAPI.Elpris pris) {
        int startHour = pris.timeStart().getHour();
        int endHour = pris.timeEnd().getHour();

        if (endHour == 0 && startHour == 23) {
            return String.format("%02d-00", startHour);
        }

        return String.format("%02d-%02d", startHour, endHour);
    }

    private static String formatOre(double sekPerKWh) {
        double ore = sekPerKWh * 100.0;
        return String.format(Locale.forLanguageTag("sv-SE"), "%.2f", ore);
    }
}

