package util.sevilla.bancodealimentos.es;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;

/**
 * Utilidad unificada para la integración con Microsoft SharePoint (Graph SDK v6).
 */
public class SharePointUtil {

    private static final Logger logger = LoggerFactory.getLogger(SharePointUtil.class);

    // Credenciales de la aplicación en Azure
    private static final String CLIENT_ID = "e15a2c05-a43a-487f-a78b-aa7fade86e7a";
    private static final String TENANT_ID = "aaea4167-1f45-4bba-ac5a-0bfb33dd3dec";
    private static final String CLIENT_SECRET = "2HR8Q~TTaWnWOoKCbJJ-A1Ipc2IGQX1tRqFiQdpd";

    /**
     * ID del sitio principal de SharePoint.
     */
    public static final String SITE_ID = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    
    /**
     * Alias para el ID del sitio de voluntarios.
     */
    public static final String SP_SITE_ID_VOLUNTARIOS = SITE_ID;

    /**
     * ID del sitio de informática.
     */
    public static final String SP_SITE_ID_INFORMATICA = SITE_ID;

    // Nombres de las listas
    public static final String LIST_NAME_TIENDAS = "Tiendas";

    private static GraphServiceClient graphClient = null;

    /**
     * Inicializa el cliente de Microsoft Graph de forma segura (Singleton).
     */
    private static synchronized void initializeGraphClient() {
        if (graphClient == null) {
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(CLIENT_ID)
                .tenantId(TENANT_ID)
                .clientSecret(CLIENT_SECRET)
                .build();
            String[] scopes = new String[] { "https://graph.microsoft.com/.default" };
            graphClient = new GraphServiceClient(credential, scopes);
        }
    }

    /**
     * Recupera todas las listas de un sitio específico.
     */
    public static ListCollectionResponse getAllLists(String siteId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(siteId).lists().get();
    }

    /**
     * Obtiene la definición de las columnas de una lista específica.
     */
    public static ColumnDefinitionCollectionResponse getListColumns(String siteId, String listId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(siteId).lists().byListId(listId).columns().get();
    }

    /**
     * Recupera todos los elementos de una lista específica.
     */
    public static ListItemCollectionResponse getListItems(String siteId, String listId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(config -> {
            config.queryParameters.expand = new String[]{"fields"};
        });
    }

    /**
     * Elimina todos los elementos de una lista de SharePoint de forma secuencial.
     */
    public static void deleteAllListItems(String siteId, String listId) throws Exception {
        initializeGraphClient();
        ListItemCollectionResponse response = getListItems(siteId, listId);
        if (response != null && response.getValue() != null) {
            for (ListItem item : response.getValue()) {
                deleteListItem(siteId, listId, item.getId());
            }
        }
    }

    /**
     * Recupera los datos de una tienda desde SharePoint usando el código como filtro.
     * Utilizado para la funcionalidad de 'Refrescar' desde la tabla local.
     */
    public static Map<String, Object> getTiendaFromSP(String codigo) throws Exception {
        initializeGraphClient();
        String listId = getListId(SITE_ID, LIST_NAME_TIENDAS);
        if (listId == null) return null;

        // Buscamos el ítem por el campo 'codigo'
        ListItemCollectionResponse response = graphClient.sites().bySiteId(SITE_ID).lists().byListId(listId).items().get(config -> {
            config.queryParameters.filter = "fields/codigo eq '" + codigo + "'";
            config.queryParameters.expand = new String[]{"fields"};
            config.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
        });

        if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
            return response.getValue().get(0).getFields().getAdditionalData();
        }
        return null;
    }

    /**
     * Sincroniza datos hacia SharePoint (Local -> SP).
     * Determina automáticamente si debe crear o actualizar basándose en el UUID o el código.
     */
    public static void syncTienda(Map<String, Object> storeData) throws Exception {
        initializeGraphClient();
        String uuid = (String) storeData.get("SqlRowUUID");
        String codigo = String.valueOf(storeData.get("codigo"));
        String listId = getListId(SITE_ID, LIST_NAME_TIENDAS);

        Map<String, Object> fieldsMap = new HashMap<>();
        fieldsMap.put("Title", String.valueOf(storeData.get("denominacion"))); 
        fieldsMap.put("SqlRowUUID", uuid); 
        fieldsMap.put("codigo", codigo); 
        fieldsMap.put("denominacion", String.valueOf(storeData.get("denominacion")));
        fieldsMap.put("direccion", String.valueOf(storeData.get("direccion")));
        fieldsMap.put("cp", String.valueOf(storeData.get("cp")));
        fieldsMap.put("poblacion", String.valueOf(storeData.get("poblacion")));
        fieldsMap.put("cadena", String.valueOf(storeData.get("cadena")));
        fieldsMap.put("prioridad", parseNumber(storeData.get("prioridad")));
        fieldsMap.put("lat", parseNumber(storeData.get("lat")));
        fieldsMap.put("lon", parseNumber(storeData.get("lon")));
        fieldsMap.put("huecosTurno1", parseNumber(storeData.get("huecosTurno1")));
        fieldsMap.put("huecosTurno2", parseNumber(storeData.get("huecosTurno2")));
        fieldsMap.put("huecosTurno3", parseNumber(storeData.get("huecosTurno3")));
        fieldsMap.put("huecosTurno4", parseNumber(storeData.get("huecosTurno4")));

        FieldValueSet fields = new FieldValueSet();
        fields.setAdditionalData(fieldsMap);

        // Buscar si ya existe por UUID o por Código
        String itemId = findItemIdByFieldValue(SITE_ID, listId, "SqlRowUUID", uuid);
        if (itemId == null) itemId = findItemIdByFieldValue(SITE_ID, listId, "codigo", codigo);

        if (itemId != null) {
            updateListItem(SITE_ID, listId, itemId, fields);
        } else {
            createListItem(SITE_ID, listId, fields);
        }
    }

    /**
     * Utilidad para asegurar el parseo de números desde formatos mixtos.
     */
    private static Object parseNumber(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return val;
        try { return Double.parseDouble(String.valueOf(val).replace(",", ".")); } catch (Exception e) { return 0; }
    }

    /**
     * Crea un nuevo ítem en la lista especificada.
     */
    public static ListItem createListItem(String targetSiteId, String listId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        ListItem newItem = new ListItem();
        newItem.setFields(fields);
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().post(newItem);
    }

    /**
     * Actualiza un ítem existente en SharePoint mediante una operación PATCH.
     */
    public static FieldValueSet updateListItem(String targetSiteId, String listId, String itemId, FieldValueSet fields) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).fields().patch(fields);
    }

    /**
     * Elimina un ítem de una lista de SharePoint. 
     * Requerido por Servlets de Campañas y Tiendas para borrado físico.
     */
    public static void deleteListItem(String targetSiteId, String listId, String itemId) throws Exception {
        initializeGraphClient();
        graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().byListItemId(itemId).delete();
    }

    /**
     * Obtiene el ID interno de una lista basándose en su nombre visible (displayName).
     */
    public static String getListId(String targetSiteId, String listName) throws Exception {
        initializeGraphClient();
        ListCollectionResponse lists = graphClient.sites().bySiteId(targetSiteId).lists().get(config -> {
            config.queryParameters.filter = "displayName eq '" + listName + "'";
        });
        if (lists != null && lists.getValue() != null && !lists.getValue().isEmpty()) {
            return lists.getValue().get(0).getId();
        }
        return null;
    }

    /**
     * Busca el ID de un ítem filtrando por un campo específico y su valor.
     */
    public static String findItemIdByFieldValue(String siteId, String listId, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        if (fieldValue == null || fieldValue.isEmpty()) return null;
        try {
            ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(config -> {
                config.queryParameters.filter = "fields/" + fieldName + " eq '" + fieldValue + "'";
                config.queryParameters.expand = new String[]{"fields"};
                config.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            });
            if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
                return response.getValue().get(0).getId();
            }
        } catch (Exception e) { 
            logger.warn("No se encontró el ítem con {} = {}", fieldName, fieldValue);
            return null; 
        }
        return null;
    }
}