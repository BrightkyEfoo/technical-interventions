# ISNOV — Projet fil rouge et programme intensif
## Angular, Ionic, Spring Boot Java et Kotlin, architecture et tests

**Brightman — révision du 18 novembre 2026.**  
**Cahier des charges pédagogique de ServiceOps Lab.** Le [journal de progression](PROGRESSION.md) indique le prochain atelier et consigne les résultats de test observés.

Cette révision remplace le cadrage initial limité à l’étude des tests. Le nouveau périmètre comprend une application web Angular, une application mobile Ionic/Angular avec Capacitor, deux implémentations équivalentes du backend en Java et en Kotlin, puis une évolution vers deux microservices. Les tests et la documentation accompagnent chaque étape.

L’objectif est de pouvoir construire, expliquer, tester, diagnostiquer et déployer le système. Les critères de passage servent à vérifier ces compétences. Le calendrier est une cible de travail, pas une promesse de niveau « expert » acquise en quatorze jours.

## 1. Le produit : ServiceOps Lab

Application fictive de gestion de demandes et d’interventions techniques. Utiliser exclusivement des données de démonstration.

Un demandeur signale un incident. Un responsable l’affecte à un technicien. Le technicien intervient depuis son mobile, ajoute un compte rendu et éventuellement une photo, puis marque la demande comme résolue. Le demandeur ou le responsable clôture la demande. Une notification interne est créée lorsqu’une affectation est réalisée.

### Rôles proposés

| Rôle | Accès métier |
| --- | --- |
| Demandeur | S’inscrire, créer et consulter ses demandes, commenter et clôturer ses demandes résolues |
| Technicien | Consulter les demandes qui lui sont affectées ; démarrer et résoudre ses interventions |
| Responsable | Consulter toutes les demandes ; affecter/réaffecter une demande non commencée ; clôturer une demande résolue |
| Super admin | Consulter toutes les interventions en lecture, parcourir l’annuaire et créer les comptes responsables et techniciens depuis le web |

L’authentification et l’appartenance à un rôle ne suffisent pas : l’API contrôle également le lien entre l’utilisateur et chaque demande. Un technicien ne peut pas consulter une intervention affectée à un autre technicien.

### Écrans à réaliser

| Client | Écrans et comportements |
| --- | --- |
| Angular web | Connexion, inscription demandeur, gestion des comptes par le super admin, liste filtrée/paginée, création, détail et historique, affectation, synthèse et Kanban |
| Ionic mobile | Connexion, inscription demandeur, mes interventions, détail, démarrage/résolution, capture de photo, état réseau |
| Mode hors ligne Ionic | Consultation des interventions déjà chargées, brouillon local conservé après fermeture de l’application, confirmation de l’envoi à la reconnexion et reprise sans doublon |

Le mobile est une véritable application Ionic utilisant Angular et Capacitor. Partager les contrats TypeScript et la logique réutilisable ; garder des interfaces adaptées à chaque support.

Le Kanban comporte cinq colonnes dans l’ordre `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED`. Construire d’abord l’affichage, puis ajouter les déplacements pour les actions autorisées. Déposer une carte vers `ASSIGNED` ouvre le choix du technicien. La carte change de colonne après confirmation de l’API.

### Règles d’acceptation à figer avant le code

| ID | Règle |
| --- | --- |
| R01 | Un titre, après suppression des espaces aux extrémités, contient entre 5 et 120 caractères |
| R02 | Une nouvelle demande commence au statut OPEN |
| R03 | Seul un responsable peut affecter une demande à un utilisateur ayant le rôle TECHNICIAN |
| R04 | La progression autorisée est OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED |
| R05 | Seul le technicien affecté peut démarrer puis résoudre l’intervention ; un compte rendu de 10 à 2 000 caractères est requis pour résoudre |
| R06 | Seul le demandeur propriétaire ou un responsable peut clôturer une demande résolue |
| R07 | Une demande clôturée refuse les modifications métier |
| R08 | Le demandeur ne consulte que ses demandes ; le technicien seulement ses affectations ; le responsable consulte tout |
| R09 | Deux changements simultanés basés sur la même version ne doivent pas s’écraser silencieusement : un seul réussit, l’autre obtient un conflit |
| R10 | Une création répétée avec la même clé d’idempotence et le même contenu produit une seule demande ; même clé avec contenu différent = conflit |
| R11 | Une affectation validée produit une seule notification interne observable, même si son événement est reçu plusieurs fois |
| R12 | Une panne du système de notifications n’annule pas une affectation déjà enregistrée ; la notification est reprise après rétablissement |
| R13 | Les listes sont paginées avec un ordre déterministe ; filtres et total sont cohérents |
| R14 | Une photo refusée ou indisponible n’empêche pas la saisie du compte rendu ; le fichier accepté doit respecter le type et la limite définis |
| R15 | Une réponse tardive à une ancienne recherche ne remplace jamais les résultats de la recherche actuelle |
| R16 | Un compte rendu envoyé après une modification concurrente affiche le conflit et permet une résolution explicite, sans écrasement automatique |
| R17 | Le Kanban affiche chaque demande visible dans la colonne de son statut et applique les droits de consultation de R08 |
| R18 | Un déplacement utilise la commande métier et la version attendue. En cas de refus ou de conflit, la carte garde son dernier statut confirmé et l’interface affiche le motif |
| R19 | Le technicien peut consulter hors ligne ses interventions déjà chargées et retrouver un brouillon de compte rendu après fermeture et réouverture. L’envoi reprend après confirmation à la reconnexion |
| R20 | Après perte de la réponse serveur, la relance de la même commande de résolution renvoie le résultat déjà enregistré et produit un seul compte rendu. La même clé avec un contenu différent provoque un conflit |
| R21 | Seul le super admin crée des comptes responsables et techniciens. Le membre remplace son mot de passe temporaire à sa première connexion |
| R22 | L’inscription publique attribue uniquement le rôle métier REQUESTER. Le compte peut ensuite créer ses propres demandes |
| R23 | Seul le super admin peut rechercher et parcourir les utilisateurs. L’annuaire affiche leur rôle et leur état |
| R24 | Le super admin peut lire toutes les interventions et leur historique. Les mutations suivent les droits des rôles opérationnels |

Paramètres pédagogiques proposés : limite photo 5 Mo, formats JPEG/PNG ; comptes de test prédéfinis ; priorité LOW/NORMAL/HIGH. Documenter toute modification de ces choix. Pas de suppression définitive de demande dans le premier périmètre.

## 2. Deux backends réellement équivalents

Concevoir un contrat OpenAPI commun, puis deux implémentations indépendantes :

- Variante Java : Spring Boot, Java et persistance PostgreSQL.
- Variante Kotlin : Spring Boot, Kotlin idiomatique et persistance PostgreSQL.
- Même contrat HTTP, mêmes droits, mêmes règles, mêmes formats d’erreur.
- Les clients sélectionnent la variante par configuration d’URL.
- Une suite d’acceptation externe commune s’exécute sur chaque variante.
- Chaque variante possède ses propres données de test. Ne pas exécuter des migrations concurrentes sur une base partagée.

La parité inclut la validation, la pagination, les droits, l’idempotence et les conflits. Comparer les propriétés métier, pas des UUID ou horodatages générés indépendamment.

| Matrice d’exécution obligatoire | Backend Java | Backend Kotlin |
| --- | --- | --- |
| Angular web : création, affectation, suivi et Kanban | À implémenter et exécuter | À implémenter et exécuter |
| Ionic web : consultation et compte rendu | À implémenter et exécuter | À implémenter et exécuter |
| Application native : parcours, plugin photo et reprise hors ligne | À valider sur une plateforme | À valider sur la même plateforme |
| Suite HTTP commune et contrat | À implémenter et exécuter | À implémenter et exécuter |

### Proposition de contrat HTTP

Préfixe commun : `/api/v1`. La connexion passe par un fournisseur OIDC de développement, par exemple Keycloak, et Spring Security protège les API.

| Méthode / route | Intention |
| --- | --- |
| POST /requests | Créer une demande avec une clé d’idempotence |
| GET /requests | Lister selon droits, recherche, statut et pagination |
| GET /requests/{id} | Consulter un détail autorisé |
| POST /requests/{id}/assign | Affecter ou réaffecter avant démarrage |
| POST /requests/{id}/start | Démarrer l’intervention |
| POST /requests/{id}/resolve | Enregistrer la résolution et le compte rendu avec une clé d’idempotence pour la reprise mobile |
| POST /requests/{id}/close | Clôturer |
| POST /requests/{id}/comments | Commenter une demande accessible non clôturée |
| POST /requests/{id}/attachments | Ajouter une photo autorisée |
| GET /notifications | Lire les notifications de l’utilisateur connecté |
| POST /admin/accounts | Créer un compte responsable ou technicien, réservé au super admin |
| GET /admin/accounts | Rechercher et parcourir les comptes utilisateurs, réservé au super admin |
| GET /technicians | Charger les techniciens actifs pour l’affectation, réservé au responsable |

Spécifier les corps, les erreurs et la version attendue des commandes dans OpenAPI. Convention proposée : 400 validation, 401 authentification invalide, 403 opération interdite sur une ressource connue, 404 ressource inexistante ou volontairement masquée, 409 conflit métier/version/idempotence, 413 fichier trop volumineux. Utiliser un format d’erreur commun contenant un code métier stable. Figer les choix 403/404 dans les tests.

## 3. Architecture à construire progressivement

### Étape A — application modulaire

Commencer par un backend déployable contenant les modules demandes/interventions et notifications. Cela permet de stabiliser les règles avant d’ajouter les pannes réseau et la cohérence différée.

Dans chaque module métier :

| Zone | Responsabilité | Dépendances permises |
| --- | --- | --- |
| Domaine | Entités métier, objets-valeurs, invariants, transitions | Types métier et bibliothèque standard |
| Application | Cas d’usage, ports d’entrée/sortie, orchestration | Domaine et interfaces nécessaires |
| Adaptateurs entrants | REST, mapping requête/réponse, validation de transport | Cas d’usage |
| Adaptateurs sortants | JPA, stockage, messages, services externes | Ports et domaine |
| Configuration | Assemblage Spring et frontières transactionnelles | Éléments nécessaires à l’assemblage |

Le domaine ne dépend ni d’un contrôleur HTTP ni de JPA. Un contrôleur ne lit pas directement un repository. Tester les règles de dépendances avec ArchUnit. Spring Modulith est une option pour vérifier les frontières entre modules et les cycles ; ce n’est pas un prérequis pour écrire les premières règles métier. [S01, S02]

**POO attendue :** comportement encapsulé dans l’entité, états valides, composition, interfaces étroites, immutabilité adaptée. Une méthode `resolve(report, actor)` doit protéger ses invariants ; des setters publics laissant n’importe quel code forcer un statut ne suffisent pas.

**SOLID attendu :** donner un exemple concret pour chaque principe, avec son coût. Ajouter un type de stockage via un port peut justifier l’inversion des dépendances ; une interface systématique devant chaque classe sans besoin identifié n’apporte pas cette preuve.

### Étape B — extraction d’un microservice

Extraire le module notifications pour obtenir deux services déployables :

| Service | Données dont il est propriétaire | Communications |
| --- | --- | --- |
| Operations | Demandes, commentaires, affectations, versions et outbox | HTTP pour les clients ; publication d’événements |
| Notifications | Notifications internes et événements déjà traités | Consommation RabbitMQ ; HTTP de lecture |

Reproduire cette découpe dans les deux variantes Java et Kotlin. Le contrat d’événement reste commun : `eventId`, type/version de schéma, `requestId`, version métier, instant, destinataire et identifiant de corrélation. Ne pas échanger une entité JPA sérialisée.

Une instance PostgreSQL locale peut héberger plusieurs bases et utilisateurs isolés pour économiser les ressources ; chaque service reste propriétaire de ses données. Pas de jointure ni d’accès direct aux tables de l’autre service. Exécuter une seule variante à la fois sur le Mac.

**Fiabilité à implémenter et démontrer :**

1. L’affectation et l’événement d’outbox sont écrits dans la même transaction.
2. Un relais publie les événements et attend une confirmation du broker avant de marquer leur publication.
3. Un événement peut être livré plusieurs fois.
4. Le consommateur enregistre l’identifiant traité et la notification dans une même transaction, protégée par une contrainte unique.
5. Il acquitte le message après validation de cette transaction.
6. Les erreurs transitoires ont des reprises bornées ; les erreurs persistantes vont dans une file dédiée avec diagnostic.

Cette conception proposée vise une livraison au moins une fois et un effet métier idempotent. Elle ne promet pas une exécution magique « exactement une fois ». RabbitMQ documente le rôle conjoint des producteurs, consommateurs et mécanismes d’acquittement. [S03]

Pour le premier périmètre, la notification est un enregistrement interne. L’envoi d’un vrai email introduit un effet externe supplémentaire dont la déduplication dépend aussi du fournisseur ; l’ajouter ensuite.

### Décisions d’architecture à rédiger

Rédiger cinq courtes ADR : application modulaire puis extraction ; Spring MVC/JPA ; contrat commun Java/Kotlin ; mécanisme d’authentification ; outbox et déduplication. Pour chaque décision : problème, options, choix, conséquences et preuve par test ou mesure.

Un reverse proxy suffit au premier déploiement. API Gateway, service discovery, Kafka, Kubernetes et service mesh constituent des extensions à justifier par un besoin mesuré.

## 4. Concepts avancés et preuves attendues

### Angular et RxJS

| Concept | Utilisation dans l’application | Test ou démonstration |
| --- | --- | --- |
| Standalone, injection, organisation par fonctionnalité | Pages et services cohérents | Remplacer un port HTTP pendant le test |
| Signals et computed | État local et compteurs dérivés | Un changement métier met à jour la vue attendue |
| Formulaires réactifs typés | Création et compte rendu | Bornes de validation et affichage des erreurs |
| RxJS debounceTime / distinctUntilChanged / switchMap | Recherche avec réponses arrivant dans le désordre | Seule la recherche actuelle met à jour la liste |
| combineLatest | Combiner recherche, statut et pagination | Les changements de filtres produisent la bonne requête |
| exhaustMap | Ignorer une seconde soumission pendant la première | Un double clic ne lance pas deux appels simultanés |
| concatMap | Sérialiser des opérations locales lorsque l’ordre compte | Ordre préservé et comportement d’erreur défini |
| catchError, retry borné | Message d’erreur et reprise des lectures appropriées | Le flux reste utilisable après une erreur |
| async pipe / takeUntilDestroyed | Gestion des abonnements selon la version | Retour/navigation sans accumulation de traitements |
| OnPush et chargement différé | Réduire le travail de rendu et charger les pages à la demande | Mesure et comportement correct, sans affirmation de gain non mesuré |
| Angular CDK et Kanban | Déplacer une carte pour déclencher une action métier | Confirmer le changement après un succès et garder la carte dans sa colonne si l’API refuse [S22] |

L’anti-double-clic frontend ne remplace pas l’idempotence du backend. Désabonner une requête avec `switchMap` évite notamment ses résultats obsolètes côté UI ; cela ne garantit pas l’annulation d’un traitement déjà commencé côté serveur.

Prévoir des marble tests avec le TestScheduler pour les opérateurs temporels et la souscription/désinscription. Les tests HTTP et composants complètent ces vérifications. [S04]

### Spring Boot Java

À travailler au fil des fonctionnalités : injection par constructeur, composition, records pour certains DTO/objets-valeurs, collections et Streams, exceptions métier, validation, gestion centralisée des erreurs, transactions, JPA, relations et piège N+1, pagination/index, migrations Flyway, verrouillage optimiste, Spring Security, logs structurés et métriques.

Le laboratoire concurrence doit provoquer deux affectations simultanées à partir d’une même version et démontrer un seul succès. Employer une synchronisation explicite dans le test ; ne pas compter sur un `sleep` pour fabriquer la course.

### Spring Boot Kotlin

Réimplémenter les mêmes cas d’usage en utilisant les idiomes du langage : null-safety, types non nullables, `data class` pour les DTO adaptés, classes scellées, extensions raisonnables, collections, expressions et immutabilité. Éviter les `!!` utilisés pour masquer une mauvaise modélisation.

Vérifier les plugins et conventions nécessaires pour les proxies Spring et les entités JPA. Ne pas transformer systématiquement une entité JPA en `data class` : identité, relations et méthodes générées doivent être maîtrisées. Initializr et la documentation Spring Boot fournissent une configuration Kotlin cohérente. [S05]

### Threads, asynchronisme et coroutines

Prévoir un atelier comparatif sur une petite agrégation de données :

- Version Java : exécution bornée, futures ou tâches structurées selon le JDK retenu, timeout et propagation d’erreur.
- Version Kotlin : concurrence structurée, annulation et timeout avec coroutines.
- Injecter une dépendance lente puis une défaillance ; mesurer durée et annulation.
- Expliquer concurrence, parallélisme, saturation des pools et coût des appels bloquants.

Conserver MVC/JPA pour le cœur afin de comparer les deux langages sur la même architecture. Un mot-clé `suspend`, un thread virtuel ou `@Async` ne transforme pas une requête JPA bloquante en accès réactif. Les coroutines et les exécuteurs sont des sujets à utiliser avec une compréhension explicite de leur modèle d’exécution. [S06, S07]

### Ionic et Capacitor

La version mobile doit traiter les transitions de pages, les overlays, le clavier, les erreurs réseau et l’accès à la caméra. Tester la différence entre cycle de vie Angular et réentrée sur une page Ionic.

Séparer le plugin natif d’un service applicatif substituable. Conserver localement les interventions déjà chargées et les brouillons par compte. Associer au brouillon la version sur laquelle il a été rédigé. Après reconnexion, vérifier l’état courant de l’intervention et demander au technicien de confirmer l’envoi. En cas de conflit, conserver le brouillon et permettre une décision explicite. Affecter un identifiant stable à la commande confirmée pour permettre sa relance si la réponse serveur se perd.

Les tests Playwright sur Ionic web vérifient la partie navigateur ; une validation sur appareil ou émulateur natif vérifie les permissions, le plugin et le retour dans l’application. Ne pas annoncer iOS validé après seulement un test Android. [S08, S09]

## 5. Stratégie de tests sur tout le système

| Niveau | Outil de départ proposé | Ce qui doit être prouvé |
| --- | --- | --- |
| Domaine Java | JUnit Jupiter ; doublures simples, Mockito si utile | Invariants, transitions, droits métier |
| Domaine Kotlin | JUnit Jupiter ; doublures simples, MockK si utile | Mêmes règles et cas limites |
| Cas d’usage | JUnit et implémentations en mémoire des ports | Orchestration et gestion des erreurs |
| Architecture | ArchUnit ; Spring Modulith en complément | Frontières et absence de cycles interdits |
| REST / sécurité | Tests MVC ciblés et tests d’API réelle | Validation, authentification, droits par objet |
| Persistance | PostgreSQL avec Testcontainers | Migrations, contraintes, transactions, concurrence |
| Messagerie | RabbitMQ avec Testcontainers | Outbox, redélivrance, reprise et absence de doublon métier |
| Angular / Ionic | TestBed et runner compatible, Vitest si supporté | Formulaires, composants, HTTP, états UI |
| RxJS | TestScheduler et marble tests | Temps virtuel, ordre, annulation, erreurs |
| Parité API | Suite HTTP commune indépendante du langage | Comportements Java/Kotlin équivalents |
| E2E web | Playwright avec backend réel | Parcours complets et persistance après rechargement |
| Mobile natif | Validation sur appareil ; Appium si automatisation retenue | Permissions, caméra, reprise de l’app |
| Charge / résilience | Petit scénario reproductible, outil à sélectionner | Latence, taux d’erreur, saturation et comportement sous panne |

Spring Boot documente l’intégration de Testcontainers avec JUnit et les services conteneurisés. Utiliser PostgreSQL réel pour les comportements spécifiques à la base, sans prétendre qu’un test de repository entièrement simulé les prouve. [S10]

La documentation actuelle d’Angular/Ionic présente Vitest pour les nouveaux projets. Un dépôt imposé en Angular 19 ne dispose pas automatiquement de cette intégration : retenir un runner compatible puis documenter le choix. [S08, S11]

### Scénarios minimaux d’acceptation

1. Créer, retrouver après rechargement, puis consulter une demande.
2. Refuser un titre trop court et trop long dans le frontend et l’API.
3. Refuser l’accès d’un utilisateur à la demande d’un autre selon les règles de rôle.
4. Refuser une action de responsable réalisée par un demandeur.
5. Refuser une transition de statut interdite.
6. Refuser une résolution sans compte rendu suffisant.
7. Traiter correctement une demande inexistante.
8. Empêcher une réponse de recherche ancienne de remplacer la recherche récente.
9. Ne créer qu’une demande après répétition de la même commande idempotente.
10. Détecter le contenu différent réutilisant une même clé d’idempotence.
11. Obtenir un seul succès pour deux écritures concurrentes sur la même version.
12. Affecter malgré une panne du broker, puis publier après son retour.
13. Traiter deux livraisons du même événement sans notification interne dupliquée.
14. Refuser un JWT expiré, un émetteur incorrect et une audience non prévue.
15. Refuser une photo non conforme et gérer proprement le refus de permission.
16. En mode avion, consulter une intervention déjà chargée, saisir un brouillon, fermer et rouvrir l’application, puis retrouver le brouillon et confirmer son envoi après reconnexion.
17. Exécuter la suite de parité contre Java puis Kotlin avec bases réinitialisées.
18. Démontrer les parcours critiques sur l’appareil mobile choisi.
19. Affecter depuis le Kanban, vérifier que la carte change de colonne après succès et qu’elle garde sa colonne si le serveur refuse un déplacement.
20. Provoquer une modification distante pendant la saisie hors ligne, puis vérifier à la reconnexion que le brouillon est conservé et que le conflit est affiché.
21. Perdre la réponse d’une résolution déjà enregistrée, relancer l’envoi avec la même clé et constater la présence d’un seul compte rendu.

Les tests de sécurité doivent distinguer ceux où l’identité est simulée de ceux où une vraie signature/token est vérifiée. Un simple utilisateur simulé dans un test MVC ne valide pas le traitement cryptographique d’un JWT.

### Mesures proposées, à adapter après le premier état initial

- Tous les cas critiques retenus dans le périmètre reliés à une règle et à une preuve.
- Domaine/cas d’usage : objectif 90 % de lignes et 80 % de branches sur un périmètre explicite.
- Frontend ciblé : objectif 80 % de lignes et 70 % de branches ; exclusions justifiées.
- Exécutions répétées : rapporter le nombre d’essais, les échecs et les relances, sans déclarer une absence générale de flakiness.
- Introduire trois défauts contrôlés et montrer les tests qui les détectent, puis annuler les défauts.
- Aucun résultat « vert » fabriqué par suppression d’assertion, `skip` injustifié ou augmentation arbitraire des délais.

Les seuils sont des objectifs pédagogiques, jamais une garantie de qualité. Relever également la pertinence des assertions et la capacité de diagnostic.

## 6. Sécurité, CI/CD et exploitation

**Authentification proposée :** fournisseur OIDC de développement avec comptes de démonstration, clients adaptés au web et au mobile, et Spring Security Resource Server dans les deux variantes. Valider signature, émetteur, expiration et audience attendue ; convertir les rôles explicitement. Les guards Angular améliorent la navigation, mais l’API tranche les autorisations. [S12]

Documenter le cycle de connexion, renouvellement et déconnexion. Ne pas stocker de secret de client confidentiel dans une application publique web/mobile. Choisir le stockage des jetons selon ce modèle ; un stockage de préférences n’est pas automatiquement un coffre sécurisé. Configurer les origines autorisées et justifier la stratégie CSRF en fonction de l’usage de cookies ou de Bearer tokens.

**Pipeline à réaliser :** contrôles statiques et tests rapides sur chaque PR ; intégration avec bases/broker temporaires ; construction des applications et images ; suite HTTP commune contre chaque variante ; smoke E2E ; publication des rapports et traces. La matrice Java/Kotlin doit empêcher qu’une variante cesse de respecter le contrat.

Déployer une variante à la fois sur un environnement de démonstration : Docker Compose, reverse proxy HTTPS, secrets injectés, healthchecks, base/broker non exposés publiquement, images identifiées par version/commit. Prévoir une stratégie de retour arrière tenant compte des migrations ; revenir à une ancienne image ne restaure pas automatiquement une ancienne base.

Avec GitHub Actions si le dépôt l’utilise : workflow de validation, build/push des images, déploiement vers VPS et smoke test après mise à jour. L’implémentation des workflows fait partie du projet ; aucune publication ou modification d’un VPS existant n’est effectuée par ce programme.

Mesurer : temps de réponse d’un parcours précis, erreurs, nombre de requêtes SQL et retard des notifications. Décrire le matériel, les données et la charge avant de comparer Java et Kotlin. Ne pas attribuer une différence au langage sans avoir contrôlé les autres variables.

## 7. Parcours en 14 journées intensives

Base de planification : 6 à 8 heures de travail par journée, environ 90 à 110 heures. Réserver une marge si les prérequis Java/Spring ou l’authentification sont nouveaux. Le périmètre fonctionnel reste volontairement petit ; les extensions visent la profondeur technique.

| Jour | Réalisation personnelle | Tests / preuve exigée | Ressources |
| --- | --- | --- | --- |
| J1 | Écrire le cahier des charges, OpenAPI initial et le domaine Java | Transitions et validations sans démarrer Spring | S13, S01 |
| J2 | REST Java, persistance PostgreSQL, migrations et mapping | Contrôleur, repository réel, migration sur base vide | S10, S14 |
| J3 | Droits, OIDC, transactions et version métier | Accès par objet, token invalide, écritures concurrentes | S12, S10 |
| J4 | Angular avec liste, création, détail, formulaires et Kanban | Tests des composants et HTTP, lecture du Kanban et déplacements selon les droits | S11, S15, S22 |
| J5 | RxJS : recherche, filtres et soumission | Marble tests, réponses désordonnées, double clic | S04, S15 |
| J6 | Ionic : interventions, compte rendu et photo | Tests web, plugin simulé, première exécution native | S08, S09 |
| J7 | Domaine, REST et persistance Kotlin | Mêmes règles et mêmes réponses de validation | S05, S16 |
| J8 | Compléter sécurité et endpoints Kotlin ; connecter les clients | Suite de parité sur les deux backends | S05, S10, S12 |
| J9 | Extraire notifications et définir le contrat d’événement | Isolation des bases, producteur/consommateur Java puis Kotlin | S02, S03 |
| J10 | Outbox, déduplication et reprises bornées | Broker arrêté, redélivrance, redémarrage du consommateur | S03, S10 |
| J11 | Mode hors ligne Ionic avec consultation et brouillon, gestion des conflits et atelier concurrence | Mode avion, redémarrage, reconnexion, conflit, relance sans doublon et timeout/annulation Java et Kotlin | S06, S07, S09 |
| J12 | Docker, CI, déploiement de démonstration et observabilité | Pipeline reproductible, healthchecks, smoke test | S17, S18 |
| J13 | Renforcer les tests de régression et mesurer la latence | Défauts injectés détectés et résultats de mesure documentés | S20 |
| J14 | Refaire un parcours à froid, corriger et présenter | Démo sur les deux variantes ; bilan honnête des limites | Toutes selon les blocages |

**Paliers de démonstration :** J3 API Java testée ; J6 web/mobile reliés au Java ; J8 équivalence Kotlin ; J10 microservices et pannes maîtrisées ; J14 restitution complète.

Si une journée déborde, terminer son critère de passage avant d’ajouter une technologie. Une première restitution peut présenter le dernier palier terminé et le reste du backlog ; elle ne doit pas faire passer un élément prévu pour un élément validé.

**Extensions après le socle :** refresh de données en temps réel, traitement de fichiers à grande échelle, stratégie de cache et invalidation, tests de mutation automatisés, passerelle API si nécessaire, comparaison avec WebFlux et R2DBC, publication sur stores, observabilité distribuée plus complète. Elles ne remplacent aucune des quatre briques obligatoires.

## 8. Organisation du dépôt et des livrables

| Chemin proposé | Contenu |
| --- | --- |
| apps/web-angular/ | Application web |
| apps/mobile-ionic/ | Application mobile et projets Capacitor |
| backends/java/ | Version Java ; opérations et notifications après extraction |
| backends/kotlin/ | Version Kotlin équivalente |
| contracts/ | OpenAPI, schémas des événements, cas et jeux de données communs |
| tests/acceptance/ | Suite HTTP indépendante et matrice de parité |
| tests/e2e/ | Parcours web/mobile web et configuration des variantes |
| infra/ | Compose, reverse proxy, scripts de démarrage et déploiement |
| docs/ | Exigences, ADR, stratégie de tests, procédures et bilan |

Utiliser des wrappers et verrouiller les versions. Socle suggéré : JDK 21 ; une version stable de Spring Boot compatible avec les contraintes d’ISNOV, identique dans les deux variantes ; Kotlin et plugins cohérents avec cette version ; version Angular maintenue et compatible avec Ionic, sauf contrainte explicite de l’équipe. Vérifier les exigences système avant installation. La documentation Spring Boot consultée confirme la compatibilité de son édition actuelle avec Java 21. [S21]

Choisir le même outil de build JVM pour les deux variantes afin de concentrer la comparaison sur les langages. Gradle Kotlin DSL est une option ; conserver Maven si c’est le standard de l’équipe.

Livrables finaux : code personnel, README exécutable, schémas, contrat, tests, rapports réels, images/pipeline, procédure de déploiement et de diagnostic, comparaison Java/Kotlin.

## 9. Méthode d’apprentissage

Pour chaque notion : 30–45 minutes de lecture, une implémentation courte, des tests pertinents, puis une explication orale sans notes. Refaire un petit exercice le lendemain et quelques jours plus tard.

Utiliser IntelliJ pour les modules backend Java et Kotlin, et VS Code pour Angular et Ionic. En live coding, l’accompagnant donne la consigne et le résultat attendu. L’apprenant écrit lui-même le code et lance ses tests. Ils relisent ensuite sa tentative ensemble, expliquent les écarts et choisissent la correction. Si l’apprenant bloque, l’accompagnant commence par un indice ciblé.

L’accompagnement suit cette séquence : consigne et critères d’acceptation ; tentative personnelle ; indice ciblé ; revue du code et des tests ; correction expliquée ; nouvel exercice de transfert. Demander une solution complète uniquement après avoir identifié ce qui bloque et pouvoir l’expliquer.

Journal quotidien : problème rencontré, hypothèse, preuve recueillie, correction, règle retenue et question encore ouverte. Les captures d’écran complètent les logs/tests, elles ne les remplacent pas.

## 10. Premier exercice — Domaine Java et affectation

**But : construire le cœur métier de l’affectation en Java avec ses tests.**

1. Écrire les types RequestId, UserId, Role, RequestStatus et l’objet Request avec ses invariants.
2. Définir les cas d’usage CreateRequest et AssignRequest, et le port RequestRepository.
3. Utiliser une implémentation en mémoire du repository et une horloge injectée si nécessaire.
4. Écrire un test avant chaque règle importante et constater son échec pour la bonne raison.
5. Ajouter un schéma simple des dépendances et une explication du choix des objets.

Tests à produire : création valide ; titre inférieur à la borne ; titre supérieur à la borne ; affectation par un responsable ; refus d’un demandeur ; refus d’un destinataire non technicien ; refus de transition après démarrage ; demande inexistante ; préservation de l’état après refus.

**Critère de passage :** tous les cas retenus sont vérifiés sans Spring ni base ; tu peux expliquer qui protège la règle, pourquoi le repository est un port et comment le remplacer par PostgreSQL. Le contrôleur HTTP arrive ensuite.

La concurrence réelle et l’idempotence persistante seront ajoutées avec PostgreSQL : un repository en mémoire ne constitue pas une preuve suffisante pour ces comportements.

## 11. Grille de restitution

| Domaine | Points proposés | Preuve |
| --- | --- | --- |
| Fonctionnement et règles métier | 20 | Parcours nominal et refus justifiés |
| Architecture et POO | 15 | Dépendances maîtrisées et tests d’architecture |
| Tests et diagnostic | 25 | Cas critiques, vrais composants intégrés et défauts détectés |
| Angular/RxJS/Ionic | 15 | Réactivité correcte, mobile et plugin validés |
| Équivalence Java/Kotlin | 10 | Contrat et acceptation communs |
| Microservices et résilience | 10 | Panne, reprise et déduplication démontrées |
| CI/déploiement/documentation | 5 | Reproduction par une autre personne |

Cible pédagogique : 80/100 minimum, avec correction des défauts critiques d’autorisation, de perte de données ou de parité avant de déclarer le périmètre terminé. Distinguer prévu, implémenté, exécuté et validé. Aucun de ces points n’est un résultat acquis à la date de rédaction.

## 12. Ressources officielles par sujet

Sources consultées ou référencées pour ce programme le 18 novembre 2026. Adapter les guides à la version verrouillée dans le projet.

- **S01 — ArchUnit :** https://www.archunit.org/userguide/html/000_Index.html — dépendances, couches, cycles.
- **S02 — Spring Modulith :** https://docs.spring.io/spring-modulith/reference/verification.html — frontières entre modules.
- **S03 — RabbitMQ :** https://www.rabbitmq.com/docs/reliability — acquittements, confirmations, pannes et redélivrance.
- **S04 — RxJS, marble testing :** https://rxjs.dev/guide/testing/marble-testing — TestScheduler ; source officielle lisible : https://github.com/ReactiveX/rxjs/blob/master/apps/rxjs.dev/content/guide/testing/marble-testing.md.
- **S05 — Spring Boot / Kotlin :** https://docs.spring.io/spring-boot/reference/features/kotlin.html — initialisation, plugins et nullabilité.
- **S06 — Kotlin / coroutines :** https://kotlinlang.org/docs/coroutines-basics.html — suspension et concurrence.
- **S07 — Spring / exécution :** https://docs.spring.io/spring-framework/reference/integration/scheduling.html — exécuteurs et tâches.
- **S08 — Ionic / tests :** https://ionicframework.com/docs/angular/testing — tests des pages, services et parcours web.
- **S09 — Capacitor :** https://capacitorjs.com/docs/basics/workflow et https://capacitorjs.com/docs/guides/mocking-plugins — exécution native et isolation des plugins.
- **S10 — Testcontainers / Spring Boot :** https://docs.spring.io/spring-boot/reference/testing/testcontainers.html — bases et services réels en test.
- **S11 — Angular / tests :** https://angular.dev/guide/testing ; référence v19 : https://v19.angular.dev/guide/testing.
- **S12 — Spring Security :** https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html — validation JWT.
- **S13 — Java :** https://dev.java/learn/ — langage, objets, collections et exceptions.
- **S14 — Spring, premiers guides :** https://spring.io/guides/gs/rest-service et https://spring.io/guides/gs/accessing-data-jpa — REST et JPA.
- **S15 — Angular / fondamentaux :** https://angular.dev/essentials et https://angular.dev/guide/signals — composants, injection et état réactif.
- **S16 — Kotlin :** https://kotlinlang.org/docs/getting-started.html — bases du langage avant Spring.
- **S17 — Docker Compose :** https://docs.docker.com/compose/ — services, réseaux et volumes.
- **S18 — GitHub Actions :** https://docs.github.com/en/actions — CI et déploiement.
- **S20 — Playwright / bonnes pratiques :** https://playwright.dev/docs/best-practices — isolation, sélecteurs et assertions observables.
- **S21 — Spring Boot / prérequis :** https://docs.spring.io/spring-boot/system-requirements.html — compatibilité Java et outils de build.
- **S22 — Angular CDK** · [Drag and drop](https://angular.dev/guide/drag-drop) · Déplacement de cartes entre colonnes.

## Ateliers complémentaires

- MFA avec Keycloak. Enrôle une application TOTP, essaie un code incorrect puis un code correct et explique à quel moment le jeton peut être émis. [Guide Keycloak](https://www.keycloak.org/docs/26.4.7/server_admin/index.html)
- Observabilité. Génère des lectures et des créations, puis compare le débit, la proportion de 5xx et le p95 dans Grafana. [Actuator](https://docs.spring.io/spring-boot/reference/actuator/metrics.html) · [Grafana](https://grafana.com/docs/grafana/latest/administration/provisioning/)
