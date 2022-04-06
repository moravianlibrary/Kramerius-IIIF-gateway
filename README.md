# iiif

How it works:
- the script generates IIIF Presentation API v3 with canvas sizes 1000x1000
- adding /full at the end of the query (not for ?link) will generate reel image sizes (only for documents with images available through IIIF image API)
- works for libraries registered in registr.digitalniknihovna.cz (if the Kramerius listed there works) and mzkk7 to test the API of Kramerius version 7

URL format:

https://iiif.digitalniknihovna.cz/kod_knihovny/uuid - withut real image sizes
https://iiif.digitalniknihovna.cz/kod_knihovny/uuid/full - with real image sizes

Or
https://iiif.digitalniknihovna.cz/?link='link to the digital library'

Example:
https://iiif.digitalniknihovna.cz/?link=https://www.digitalniknihovna.cz/mzk/view/uuid:6dfb1870-a657-11eb-94e5-005056827e52?page=uuid:d8ea53a7-278a-42ab-b05c-a4b6dc9e8450

Examples for different types of documents: 

Monograph

https://iiif.digitalniknihovna.cz/mzk/uuid:ca15a389-e5eb-4c19-b0d0-f919066ef2ba

Periodical title

https://iiif.digitalniknihovna.cz/mzk/uuid:ae81bb20-435d-11dd-b505-00145e5790ea


Periodical volume

https://iiif.digitalniknihovna.cz/mzk/uuid:b2268013-435d-11dd-b505-00145e5790ea

Periodical item

https://iiif.digitalniknihovna.cz/mzk/uuid:ee18e055-435d-11dd-b505-00145e5790ea

Graphic

https://iiif.digitalniknihovna.cz/mzk/uuid:e8933b86-fecc-45cc-962c-d22e9271eee4

Map

https://iiif.digitalniknihovna.cz/mzk/uuid:ba4934d1-0a1e-4a01-a89d-c948477ca833

Sheet music

https://iiif.digitalniknihovna.cz/mzk/uuid:61f6fddf-27ca-4acc-a756-a2fedc6508b8

Archival document

https://iiif.digitalniknihovna.cz/mzk/uuid:b85c556b-07e1-46c7-b342-9602009f1bd5

Manuscript

https://iiif.digitalniknihovna.cz/mzk/uuid:5e0d749d-c560-4b8c-aae7-665cb952b655

Sound recordings

https://iiif.digitalniknihovna.cz/mzk/uuid:4067ca61-96f7-48b6-9fa3-8a127a4df5e6

PDF - does not generate items, just metadata description and a link to the pdf

https://iiif.digitalniknihovna.cz/mzk/uuid:27055693-9413-43d3-8e61-d5048b14dd16

Virtual collection (K7 only)

https://iiif.digitalniknihovna.cz/mzkk7/uuid:faa04b5b-eaf7-4833-b98e-4a69eaffadba
