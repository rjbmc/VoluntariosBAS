package servlets.sevilla.bancodealimentos.es;

import com.google.gson.Gson;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.requests.ListItemCollectionPage;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.SharepointUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

public class SharepointListServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        
        // Obtener IDs desde las variables de entorno
        String siteId = System.getenv("SP_SITE_ID");
        String listId = System.getenv("SP_LIST_ID");

        // Validar que los IDs del sitio y la lista están configurados
        if (Objects.isNull(siteId) || Objects.isNull(listId) || siteId.isEmpty() || listId.isEmpty()) {
            System.err.println("Error: Faltan las variables de entorno SP_SITE_ID o SP_LIST_ID.");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "La configuración del servidor para SharePoint está incompleta.");
            return;
        }

        try {
            // 1. Obtener el cliente de Graph autenticado
            GraphServiceClient<okhttp3.Request> graphClient = SharepointUtil.getGraphClient();

            // 2. Construir la petición para obtener los items de la lista.
            //    - .expand("fields") es importante para que nos devuelva los datos de las columnas.
            ListItemCollectionPage listItems = graphClient
                .sites(siteId)
                .lists(listId)
                .items()
                .buildRequest()
                .expand("fields")
                .get();

            // 3. Convertir la lista de resultados a JSON usando GSON
            Gson gson = new Gson();
            String jsonResponse = gson.toJson(listItems.getCurrentPage());
            
            // 4. Escribir la respuesta JSON al cliente
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
