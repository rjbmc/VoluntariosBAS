package util.sevilla.bancodealimentos.es;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.HttpMethod;
import com.microsoft.kiota.RequestInformation;
import com.microsoft.kiota.serialization.ParsableFactory;

public class SharePointUtil {

    public static final String SITE_ID = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_VOLUNTARIOS = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_INFORMATICA = "bancodealimentosdsevilla.sharepoint.com,a7c6f38d-82a8-4acd-bbb2-69383beb6f54,d9d19aa9-15fb-4f97-aa37-9ce7b3de7db5";
    public static final String LIST_NAME_TIENDAS = "Tiendas";
    public static final String FIELD_CODIGO_TIENDA = "codigo";
    public static final String LIST_NAME_VOLUNTARIOS = "Voluntarios BAS";
    private static Map<Integer, String> codigosPostalesCache = new ConcurrentHashMap<>();
    private static boolean codigosPostalesCargados = false;

    private static GraphServiceClient graphClient = null;
    /**
     * Carga todos los códigos postales de la lista 'cpostales' en memoria
     */
    public static void cargarCodigosPostales(Connection conn, String siteId) throws Exception {
        if (codigosPostalesCargados) {
            LogUtil.logOperation(conn, "SP_INFO", "SYSTEM", "Los códigos postales ya están cargados en caché");
            return;
        }
        
        initializeGraphClient();
        String listId = getListId(siteId, "cpostales");
        
        if (listId == null) {
            LogUtil.logOperation(conn, "SP_WARNING", "SYSTEM", "No se pudo encontrar la lista 'cpostales'");
            return; // No es un error fatal, solo no se pueden cargar
        }
        
        LogUtil.logOperation(conn, "SP_INFO", "SYSTEM", "Cargando códigos postales desde SharePoint...");
        
        try {
            int totalCargados = 0;
            String nextLink = null;
            
            do {
                com.microsoft.graph.models.ListItemCollectionResponse response;
                
                if (nextLink == null) {
                    response = graphClient.sites().bySiteId(siteId)
                        .lists().byListId(listId)
                        .items()
                        .get(requestConfiguration -> {
                            requestConfiguration.queryParameters.expand = new String[]{"fields"};
                            requestConfiguration.queryParameters.top = 100;
                        });
                } else {
                    // Si hay más páginas, salir del bucle (implementación simplificada)
                    break;
                }
                
                if (response != null && response.getValue() != null) {
                    for (com.microsoft.graph.models.ListItem item : response.getValue()) {
                        com.microsoft.graph.models.FieldValueSet fields = item.getFields();
                        if (fields != null) {
                            Map<String, Object> fieldData = fields.getAdditionalData();
                            Integer itemId = Integer.valueOf(item.getId());
                            
                            // Buscar el campo que contiene el código postal
                            String codigoPostal = null;
                            if (fieldData.containsKey("Title")) {
                                codigoPostal = fieldData.get("Title") != null ? fieldData.get("Title").toString() : null;
                            } else if (fieldData.containsKey("CodigoPostal")) {
                                codigoPostal = fieldData.get("CodigoPostal") != null ? fieldData.get("CodigoPostal").toString() : null;
                            }
                            
                            if (codigoPostal != null && !codigoPostal.isEmpty()) {
                                codigosPostalesCache.put(itemId, codigoPostal);
                                totalCargados++;
                            }
                        }
                    }
                }
                
                nextLink = null; // Salir del bucle después de la primera página
                
            } while (nextLink != null);
            
            codigosPostalesCargados = true;
            LogUtil.logOperation(conn, "SP_INFO", "SYSTEM", "Cargados " + totalCargados + " códigos postales en caché");
            
        } catch (Exception e) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "Error cargando códigos postales: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Obtiene el valor del código postal a partir de su LookupId
     */
    public static String getCodigoPostalById(Integer lookupId) {
        if (lookupId == null || lookupId == 0) {
            return "";
        }
        
        String codigoPostal = codigosPostalesCache.get(lookupId);
        if (codigoPostal == null) {
            return String.valueOf(lookupId); // Devuelve el ID como fallback
        }
        
        return codigoPostal;
    }

    /**
     * Método auxiliar para limpiar la caché (útil para pruebas)
     */
    public static void limpiarCacheCodigosPostales() {
        codigosPostalesCache.clear();
        codigosPostalesCargados = false;
    }
    
    
    public static void listarTodasLasListasAccesibles() throws Exception {
        initializeGraphClient();
        
        // Probar con los site IDs que tienes
        String[] siteIds = {
            "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db",
            "bancodealimentosdsevilla.sharepoint.com,aaea4167-1f45-4bba-ac5a-0bfb33dd3dec"
        };
        
        for (String siteId : siteIds) {
            System.out.println("\n=== SITE ID: " + siteId + " ===");
            try {
                // Obtener todas las listas de este sitio
                ListCollectionResponse lists = graphClient.sites().bySiteId(siteId).lists().get();
                
                System.out.println("Listas encontradas en este sitio:");
                for (com.microsoft.graph.models.List lista : lists.getValue()) {
                    System.out.println("  - " + lista.getDisplayName() + " (ID: " + lista.getId() + ")");
                }
            } catch (Exception e) {
                System.out.println("Error accediendo a este sitio: " + e.getMessage());
            }
        }
    }
    public static void encontrarSitioInformatica() throws Exception {
        initializeGraphClient();
        
        // El List ID que sabemos que existe en el sitio de informática
        String targetListId = "b77f5166-7221-460a-bd3c-fae26a3880c5";
        
        // Obtener todos los sitios a los que la aplicación tiene acceso
        SiteCollectionResponse sites = graphClient.sites().get();
        
        System.out.println("=== BUSCANDO SITIO QUE CONTIENE LA LISTA ===");
        System.out.println("List ID buscado: " + targetListId);
        System.out.println("Total de sitios a revisar: " + sites.getValue().size());
        System.out.println("");
        
        int contador = 0;
        for (com.microsoft.graph.models.Site site : sites.getValue()) {
            contador++;
            String siteId = site.getId();
            String siteName = site.getDisplayName();
            String siteUrl = site.getWebUrl();
            
            System.out.println(contador + ". Revisando: " + siteName);
            System.out.println("   URL: " + siteUrl);
            System.out.println("   Site ID: " + siteId);
            
            try {
                // Intentar obtener la lista por su ID
                com.microsoft.graph.models.List lista = graphClient.sites().bySiteId(siteId)
                    .lists().byListId(targetListId)
                    .get();
                
                System.out.println("");
                System.out.println(">>> ¡LISTA ENCONTRADA! <<<");
                System.out.println(">>> Site ID CORRECTO: " + siteId);
                System.out.println(">>> Site Name: " + siteName);
                System.out.println(">>> Site URL: " + siteUrl);
                System.out.println(">>> List Name: " + lista.getDisplayName());
                System.out.println("");
                System.out.println("✅ COPIA ESTE SITE ID EN TU CONSTANTE:");
                System.out.println("public static final String SP_SITE_ID_INFORMATICA = \"" + siteId + "\";");
                return;
                
            } catch (Exception e) {
                // Este sitio no tiene esa lista, continuar
                System.out.println("   -> No contiene la lista buscada");
            }
            System.out.println("");
        }
        
        System.out.println("=== NO SE ENCONTRÓ NINGÚN SITIO CON ESA LISTA ===");
        System.out.println("Posibles causas:");
        System.out.println("1. La aplicación no tiene permisos para acceder al sitio de informática");
        System.out.println("2. El List ID es incorrecto");
        System.out.println("3. El sitio de informática es un subsitio (subsite) y no aparece en la lista de sitios");
    }
    private static void initializeGraphClient() {
        if (graphClient == null) {
            final String clientId = "e15a2c05-a43a-487f-a78b-aa7fade86e7a";
            final String tenantId = "aaea4167-1f45-4bba-ac5a-0bfb33dd3dec";
            final String clientSecret = "2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd";
            
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();

            graphClient = new GraphServiceClient(credential);
        }
    }
    public static String getListId(String targetSiteId, String listName) throws Exception {
        initializeGraphClient();
        ListCollectionResponse lists = graphClient.sites().bySiteId(targetSiteId).lists().get(requestConfiguration -> {
            requestConfiguration.queryParameters.filter = "displayName eq '" + listName + "'";
        });
        if (lists != null && lists.getValue() != null && !lists.getValue().isEmpty()) {
            return lists.getValue().get(0).getId();
        }
        return null;
    }

    public static ListCollectionResponse getAllLists(String targetSiteId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().get();
    }

    public static ColumnDefinitionCollectionResponse getListColumns(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).columns().get();
    }

    public static ListItemCollectionResponse getListItems(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        
        ListItemCollectionResponse page = graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().get(requestConfiguration -> {
            requestConfiguration.queryParameters.expand = new String[]{"fields"};
        });

        if (page == null) {
            return new ListItemCollectionResponse();
        }

        final List<ListItem> allItems = new LinkedList<>();
        allItems.addAll(page.getValue());

        while (page.getOdataNextLink() != null) {
            page = graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().withUrl(page.getOdataNextLink()).get();
            if (page != null) {
                allItems.addAll(page.getValue());
            }
        }
        
        final ListItemCollectionResponse allItemsResponse = new ListItemCollectionResponse();
        allItemsResponse.setValue(allItems);
        
        return allItemsResponse;
    }

    public static ListItem createListItem(String targetSiteId, String listId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        ListItem newItem = new ListItem();
        newItem.setFields(fields);
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().post(newItem);
    }

    public static FieldValueSet updateListItem(String targetSiteId, String listId, String itemId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).fields().patch(fields);
    }

    public static void deleteListItem(String targetSiteId, String listId, String itemId) throws Exception {
        initializeGraphClient();
        graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).delete();
    }

    public static void deleteAllListItems(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        ListItemCollectionResponse items = getListItems(targetSiteId, listId);
        if (items != null && items.getValue() != null) {
            for (ListItem item : items.getValue()) {
                deleteListItem(targetSiteId, listId, item.getId());
            }
        }
    }

    public static String findItemIdByFieldValue(Connection conn, String siteId, String listId, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        if (fieldValue == null || fieldValue.isEmpty()) {
            return null;
        }

        String escapedValue = fieldValue.replace("'", "''");
        String filter = "fields/" + fieldName + " eq '" + escapedValue + "'";

        try {
            ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(requestConfiguration -> {
                requestConfiguration.queryParameters.filter = filter;
                requestConfiguration.queryParameters.select = new String[]{"id"};
                requestConfiguration.queryParameters.top = 1;
                requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            });

            if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
                return response.getValue().get(0).getId();
            }
        } catch (Exception e) {
            LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "Error en findItemIdByFieldValue: " + e.getMessage());
            return null;
        }
        return null;
    }
    
    public static Map<String, Object> findItemByFieldValue(Connection conn, String siteId, String listName, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        String listId = getListId(siteId, listName);
        if (listId == null) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "findItemByFieldValue: No se pudo encontrar el listId para la lista: " + listName);
            throw new IOException("La lista de SharePoint '" + listName + "' no fue encontrada.");
        }

        if (fieldValue == null || fieldValue.isEmpty()) {
            return null;
        }

        String escapedValue = fieldValue.replace("'", "''");
        String filter = "fields/" + fieldName + " eq '" + escapedValue + "'";

        try {
            com.microsoft.graph.models.ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(requestConfiguration -> {
                requestConfiguration.queryParameters.filter = filter;
                requestConfiguration.queryParameters.expand = new String[]{"fields"};
                requestConfiguration.queryParameters.top = 1;
                requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            });

            if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
                LogUtil.logOperation(conn, "SP_QUERY_SUCCESS", "SYSTEM", "Item encontrado en lista '" + listName + "' con filtro: " + filter);
                
                com.microsoft.graph.models.ListItem item = response.getValue().get(0);
                com.microsoft.graph.models.FieldValueSet fields = item.getFields();
                Map<String, Object> resultData = new HashMap<>(fields.getAdditionalData());
                
                // Si tenemos el campo lookup, añadimos el valor usando la caché
                if (resultData.containsKey("C_x002e_PostalLookupId") && !resultData.containsKey("C_x002e_Postal")) {
                    Object lookupIdObj = resultData.get("C_x002e_PostalLookupId");
                    if (lookupIdObj != null) {
                        try {
                            Integer lookupId = Integer.parseInt(lookupIdObj.toString());
                            if (lookupId > 0) {
                                String codigoPostal = getCodigoPostalById(lookupId);
                                resultData.put("C_x002e_Postal", codigoPostal);
                            }
                        } catch (NumberFormatException e) {
                            resultData.put("C_x002e_Postal", "");
                        }
                    }
                }
                
                return resultData;
            } else {
                LogUtil.logOperation(conn, "SP_QUERY_NOT_FOUND", "SYSTEM", "Item no encontrado en lista '" + listName + "' con filtro: " + filter);
                return null;
            }
        } catch (Exception e) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "Error en findItemByFieldValue con filtro '" + filter + "': " + e.getMessage());
            throw e;
        }
    }

    public static Map<String, Object> getTiendaFromSP(Connection conn, String codigoTienda) throws Exception {
        LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: Iniciando búsqueda para código: " + codigoTienda);
        initializeGraphClient();

        String listId = getListId(SITE_ID, LIST_NAME_TIENDAS);
        if (listId == null) {
            LogUtil.logOperation(conn, "SP_ERROR", "SYSTEM", "getTiendaFromSP: No se pudo encontrar el listId para la lista: " + LIST_NAME_TIENDAS);
            throw new IOException("La lista de SharePoint '" + LIST_NAME_TIENDAS + "' no fue encontrada.");
        }
        LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: listId obtenido: " + listId);

        String filter = "fields/" + FIELD_CODIGO_TIENDA + " eq " + codigoTienda;
        LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: Buscando tienda con filtro: " + filter);

        ListItemCollectionResponse response = graphClient.sites().bySiteId(SITE_ID).lists().byListId(listId).items().get(requestConfiguration -> {
            requestConfiguration.queryParameters.filter = filter;
            requestConfiguration.queryParameters.expand = new String[]{"fields"};
            requestConfiguration.queryParameters.top = 1;
            requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
        });

        if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
            LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: Se encontró la tienda para código " + codigoTienda);
            return response.getValue().get(0).getFields().getAdditionalData();
        } else {
            LogUtil.logOperation(conn, "SP_DEBUG", "SYSTEM", "getTiendaFromSP: No se encontró ninguna tienda para código " + codigoTienda + " con el filtro " + filter + ".");
            return null;
        }
    }

    public static void syncTienda(Connection conn, Map<String, Object> storeData) throws Exception {
        initializeGraphClient();
        String listId = getListId(SITE_ID, LIST_NAME_TIENDAS);
        if (listId == null) {
            throw new IOException("La lista de SharePoint '" + LIST_NAME_TIENDAS + "' no fue encontrada.");
        }

        String uuid = (String) storeData.get("SqlRowUUID");
        String itemId = findItemIdByFieldValue(conn, SITE_ID, listId, "SqlRowUUID", uuid);

        FieldValueSet fields = new FieldValueSet();
        fields.setAdditionalData(storeData);

        if (itemId != null) {
            // Update
            updateListItem(SITE_ID, listId, itemId, fields);
        } else {
            // Create
            createListItem(SITE_ID, listId, fields);
        }
    }

    public static void logError(String listName, String uuid, String message) {
        try (FileWriter writer = new FileWriter("../logs/sharepoint_sync_errors.log", true)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            writer.write(String.format("[%s] Error en lista '%s' para UUID '%s': %s\n", timestamp, listName, uuid, message));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}