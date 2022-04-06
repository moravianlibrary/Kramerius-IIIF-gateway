# iiif

Jak to funguje:
- skript generuje IIIF Presentation API v3 s velikostí obrázků 1000x1000
- přidáním /full na konec dotazu (ne pro ?link) se vygenerují reálné velikosti obrázků, ale déle to trvá
- funguje pro knihovny z registr.digitalniknihovna.cz (pokud tam mají uvedený funkční Kramerius) a mzkk7 pro test API Krameria verze 7

Formát url:

https://api.digitalniknihovna.cz/kod_knihovny/uuid - bez reálné velikosti obrázků
https://api.digitalniknihovna.cz/kod_knihovny/uuid/full - s reálnou velikostí obrázků

Nebo
https://iiif.digitalniknihovna.cz/?link='link do digitalni knihovny'

Příklad:
https://iiif.digitalniknihovna.cz/?link=https://www.digitalniknihovna.cz/mzk/view/uuid:6dfb1870-a657-11eb-94e5-005056827e52?page=uuid:d8ea53a7-278a-42ab-b05c-a4b6dc9e8450

Příklady pro různé typy dokumentů: 

Monografie

https://iiif.digitalniknihovna.cz/mzk/uuid:ca15a389-e5eb-4c19-b0d0-f919066ef2ba

Periodikum

https://iiif.digitalniknihovna.cz/mzk/uuid:ae81bb20-435d-11dd-b505-00145e5790ea


Ročník periodika

https://iiif.digitalniknihovna.cz/mzk/uuid:b2268013-435d-11dd-b505-00145e5790ea

Číslo periodika

https://iiif.digitalniknihovna.cz/mzk/uuid:ee18e055-435d-11dd-b505-00145e5790ea

Grafika

https://iiif.digitalniknihovna.cz/mzk/uuid:e8933b86-fecc-45cc-962c-d22e9271eee4

Mapa

https://iiif.digitalniknihovna.cz/mzk/uuid:ba4934d1-0a1e-4a01-a89d-c948477ca833

Hudebnina

https://iiif.digitalniknihovna.cz/mzk/uuid:61f6fddf-27ca-4acc-a756-a2fedc6508b8

Archiválie

https://iiif.digitalniknihovna.cz/mzk/uuid:b85c556b-07e1-46c7-b342-9602009f1bd5

Rukopis

https://iiif.digitalniknihovna.cz/mzk/uuid:5e0d749d-c560-4b8c-aae7-665cb952b655

Zvukové nahrávky

https://iiif.digitalniknihovna.cz/mzk/uuid:4067ca61-96f7-48b6-9fa3-8a127a4df5e6

PDF - negeneruje items, jen metadatový popis a link na pdf

https://iiif.digitalniknihovna.cz/mzk/uuid:27055693-9413-43d3-8e61-d5048b14dd16
