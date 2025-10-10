package servlets.sevilla.bancodealimentos.es;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.serviceclient.GraphServiceClient; 
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
@WebServlet("/lista-sharepoint")
public class SharepointListServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        
//      String siteId = System.getenv("SP_SITE_ID");
//      String listId = System.getenv("SP_LIST_ID");
    	String siteId = "SP_SITE_ID";
        String listId = "SP_LIST_ID";

        if (Objects.isNull(siteId) || Objects.isNull(listId) || siteId.isEmpty() || listId.isEmpty()) {
            System.err.println("Error: Faltan las variables de entorno SP_SITE_ID o SP_LIST_ID.");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "La configuración del servidor para SharePoint está incompleta.");
            return;
        }

        try {
            GraphServiceClient graphClient = SharepointUtil.getGraphClient();

            // Construir la petición para v6 (la sintaxis ha cambiado)
            List<ListItem> listItems = graphClient.sites().bySiteId(siteId)
                .lists().byListId(listId)
                .items()
                .get(requestConfiguration -> {
                    // .expand es ahora un parámetro de configuración de la petición
                    requestConfiguration.queryParameters.expand = new String[]{"fields"};
                }).getValue();

            // Usar Gson para serializar la respuesta. 
            // Usar GsonBuilder para un formato más legible si se desea.
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonResponse = gson.toJson(listItems);
            
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            PrintWriter out = response.getWriter();
            out.print(jsonResponse);
            out.flush();

        } catch (Exception e) {
            System.err.println("Error al obtener la lista de SharePoint: " + e.getMessage());
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al procesar la solicitud de SharePoint: " + e.getMessage());
        }
    }
}
