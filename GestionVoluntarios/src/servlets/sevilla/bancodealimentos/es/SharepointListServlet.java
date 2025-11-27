package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.graph.models.ColumnDefinition;
import com.microsoft.graph.models.ListCollectionResponse;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/lista-sharepoint")
public class SharepointListServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doPost(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String siteIdVoluntarios = SharepointUtil.SP_SITE_ID_VOLUNTARIOS;
        String siteIdInformatica = SharepointUtil.SP_SITE_ID_INFORMATICA;
        
        String siteSelection = request.getParameter("siteSelection");
        String listIdOrName = request.getParameter("lista"); 

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        PrintWriter out = response.getWriter();
     
        String siteId= SharepointUtil.SITE_ID;

        if ("voluntarios".equalsIgnoreCase(siteSelection)) {
            siteId = siteIdVoluntarios;
        } else if ("informatica".equalsIgnoreCase(siteSelection)) {
            siteId = siteIdInformatica;
        } 
//        else {
//            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
//            out.print(gson.toJson(Map.of("error", "No se ha especificado un sitio de SharePoint válido.")));
//            return;
//        }
        
        if (Objects.isNull(siteId) || siteId.isEmpty() || siteId.startsWith("ID_DEL_SITIO")) {
            String errorMessage = "El ID del sitio '" + siteSelection + "' no está configurado. Por favor, edita el fichero SharepointUtil.java y rellena la variable correspondiente.";
            System.err.println(errorMessage);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(gson.toJson(Map.of("error", errorMessage)));
            return;
        }

        try {
            GraphServiceClient graphClient = SharepointUtil.getGraphClient();
            
            if (listIdOrName != null && !listIdOrName.trim().isEmpty()) {
                // PASO 1: OBTENER DEFINICIONES DE COLUMNA Y MARCAR CORRECTAMENTE LOS LOOKUPS
                List<ColumnDefinition> columns = graphClient.sites().bySiteId(siteId)
                    .lists().byListId(listIdOrName)
                    .columns()
                    .get()
                    .getValue();

                List<Map<String, Object>> columnDetails = columns.stream()
                    .filter(c -> c.getHidden() == null || !c.getHidden())
                    .map(c -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("internalName", c.getName());
                        map.put("displayName", c.getDisplayName());
                        map.put("readOnly", c.getReadOnly());
                        
                        // SOLUCIÓN: Un campo es lookup si es de tipo Lookup o si es un campo proyectado (contiene _x003a_)
                        if (c.getLookup() != null || c.getName().contains("_x003a_")) {
                            map.put("isLookup", true);
                        }
                        return map;
                    })
                    .collect(Collectors.toList());

                // PASO 2: OBTENER DATOS Y PROCESARLOS CON LA LÓGICA DE DOBLE PASADA
                List<ListItem> listItems = graphClient.sites().bySiteId(siteId)
                    .lists().byListId(listIdOrName)
                    .items()
                    .get(config -> {
                        config.queryParameters.expand = new String[]{ "fields" };
                    })
                    .getValue();
                
                List<Map<String, Object>> itemDetails = new ArrayList<>();
                if (listItems != null) {
                    for (ListItem item : listItems) {
                         if (item.getFields() != null) {
                            Map<String, Object> processedData = new HashMap<>(item.getFields().getAdditionalData());

                            List<String> lookupKeys = new ArrayList<>();
                            for (String key : processedData.keySet()) {
                                if (key.endsWith("LookupId")) {
                                    lookupKeys.add(key);
                                }
                            }

                            for (String lookupKey : lookupKeys) {
                                Object value = processedData.get(lookupKey);
                                String originalKey = lookupKey.substring(0, lookupKey.length() - "LookupId".length());
                                processedData.put(originalKey, value);
                                processedData.remove(lookupKey);
                            }
                            
                            processedData.put("id", item.getId());
                            itemDetails.add(processedData);
                        }
                    }
                }

                // PASO 3: ENVIAR EL RESULTADO FINAL
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("columns", columnDetails);
                result.put("items", itemDetails);
                out.print(gson.toJson(result));

            } else {
                List<Map<String, String>> results = new ArrayList<>();
                ListCollectionResponse currentPage = graphClient.sites().bySiteId(siteId).lists().get(config -> {
                    config.queryParameters.select = new String[]{"id", "displayName", "list"};
                });

                while (currentPage != null) {
                    final List<com.microsoft.graph.models.List> listsInPage = currentPage.getValue();
                    if (listsInPage != null) {
                        for (com.microsoft.graph.models.List spList : listsInPage) {
                            boolean isHidden = spList.getList() != null && Boolean.TRUE.equals(spList.getList().getHidden());
                            boolean isDocumentLibrary = spList.getList() != null && "documentLibrary".equals(spList.getList().getTemplate());

                            if (!isHidden && !isDocumentLibrary) {
                                Map<String, String> listItem = new LinkedHashMap<>();
                                listItem.put("Nombre", spList.getDisplayName());
                                listItem.put("ID", spList.getId());
                                results.add(listItem);
                            }
                        }
                    }
                    currentPage = (currentPage.getOdataNextLink() != null) ? graphClient.sites().bySiteId(siteId).lists().withUrl(currentPage.getOdataNextLink()).get() : null;
                }
                out.print(gson.toJson(results));
            }

        } catch (Exception e) {
            System.err.println("Error al obtener datos de SharePoint: " + e.getMessage());
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(gson.toJson(Map.of("error", "Error al procesar la solicitud de SharePoint: " + e.getMessage())));
        } finally {
            out.flush();
        }
    }
}
