# go-eCharger

Ein sehr vielseitiges, kompaktes Ladegerät, das auch für den mobilen Einsatz geeignet ist, ist der [go-eCharger](https://go-e.co/go-echarger-home/).

Bei Verwendung dieser Wallbox ist kein separater Stromzähler erforderlich, weil der Zählerwert von der Wallbox selbst mit hoher Genauigkeit bereitgestellt wird und der *Smart Appliance Enabler* daraus die aktuelle Leistungsaufnahme berechnet.

## Geräte-Konfiguration

Der go-eCharger muss mit WLAN verbunden sein, in dem sich auch der *Smart Appliance Enabler* befindet oder das zumindest für ihn erreichbar ist.
Die HTTP-Schnittstelle des go-eCharger muss aktiviert werden, damit der *Smart Appliance Enabler* mit ihm darüber kommunizieren kann.

## Konfiguration im Smart Appliance Enabler

### Wallbox

Für die Konfiguration sollte die Vorlage `go-eCharger` verwendet werden - dadurch werden alle Felder korrekt ausgefüllt. Lediglich die IP-Adresse bzw. der Hostname in den URL-Feldern muss auf die des go-eChargers angepasst werden. 

![Konfiguration des go-eCharger als Schalter](../pics/fe/EVChargerGoeCharger.png)

### Zähler

Wie oben geschrieben muss der go-eCharger selbst als Zähler angegeben werden, d.h.
die IP-Adresse bzw. der Hostname in den URL-Feldern muss auf die des go-eChargers angepasst werden. 

Als `Format` muss `JSON` ausgewählt werden, damit die Antworten des go-eCharger korrekt interpretiert werden können.

Das Feld `Pfad` muss den Wert `$.dws` enthalten, damit der *Smart Appliance Enabler* weiss, an welcher Stelle in der Antwort des go-eChargers der Wert für die Energie enthalten ist. 

Im Feld `Umrechnungsfaktor` muss die Zahl `0.0000027778` eingegeben werden, weil der go-eCharger die Energie in 10 Deka-Watt-Sekunden liefert.

Als Parameter ist `Zählerstand` zu wählen.

![Konfiguration des go-eCharger als Zähler](../pics/fe/EVChargerGoeChargerMeter.png)
