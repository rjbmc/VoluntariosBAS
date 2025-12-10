package util.sevilla.bancodealimentos.es;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SharepointUtil {

    private static final Logger logger = LogManager.getLogger(SharepointUtil.class);

    public static final String SITE_ID = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_VOLUNTARIOS = "bancodealimentosdsevilla.sharepoint.com,ee4d7ea9-c8f0-45f7-864a-2da47d05c0fd,ace86285-8799-4cd6-8121-26255a3c62db";
    public static final String SP_SITE_ID_INFORMATICA = "bancodealimentosdsevilla.sharepoint.com,98b8faf3-b1b7-4a7b-8cd8-744fbe8c8c79,b2a4e075-4f56-47fe-9fc5-09466a41a27a";

    private static GraphServiceClient graphClient = null;

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

    public static ListCollectionResponse getAllLists(String siteId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(siteId).lists().get(requestConfiguration -> {
            requestConfiguration.queryParameters.select = new String[]{"id", "displayName", "webUrl"};
        });
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

    public static ColumnDefinitionCollectionResponse getListColumns(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).columns().get();
    }

    public static ListItemCollectionResponse getListItems(String targetSiteId, String listId) throws Exception {
        initializeGraphClient();
        return graphClient.sites().bySiteId(targetSiteId).lists().byListId(listId).items().get(requestConfiguration -> {
            requestConfiguration.queryParameters.expand = new String[]{"fields"};
        });
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

    public static String findItemIdByFieldValue(String siteId, String listId, String fieldName, String fieldValue) throws Exception {
        initializeGraphClient();
        if (fieldValue == null || fieldValue.isEmpty()) {
            return null;
        }

        ListItemCollectionResponse response = graphClient.sites().bySiteId(siteId).lists().byListId(listId).items().get(requestConfiguration -> {
            requestConfiguration.queryParameters.filter = "fields/" + fieldName + " eq '" + fieldValue + "'";
            requestConfiguration.queryParameters.select = new String[]{"id"};
            requestConfiguration.queryParameters.top = 1;
            requestConfiguration.headers.add("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
        });

        if (response != null && response.getValue() != null && !response.getValue().isEmpty()) {
            return response.getValue().get(0).getId();
        }

        return null;
    }

    public static void logError(String listName, String uuid, String message) {
        try {
            File logDir = new File("../logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            try (FileWriter writer = new FileWriter("../logs/sharepoint_sync_errors.log", true)) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                writer.write(String.format("[%s] Error de replicación en lista '%s' para UUID '%s': %s\n", timestamp, listName, uuid, message));
            }
        } catch (IOException e) {
            logger.error("No se pudo escribir en el fichero de errores sharepoint: {}", e.getMessage(), e);
        }
    }
}