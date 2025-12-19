package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/cleanup-tiendas")
public class CleanupTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada
    private static final String SHAREPOINT_LIST_NAME = "Tiendas";
    private static final int PAUSA_CORTA_MS = 75;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Configurar la respuesta para retransmitir texto plano en vivo
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            out.println("ERROR: Acceso denegado. No tienes permisos para realizar esta operación.");
            out.flush();
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        try {
            out.println("--- INICIANDO LIMPIEZA PROFUNDA (MODO DIAGNÓSTICO) ---");
            out.flush();

            out.println("Paso 1/4: Solicitando ID de la lista '" + SHAREPOINT_LIST_NAME + "'...");
            out.flush();
            String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) {
                throw new Exception("ERROR CRÍTICO: La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada en SharePoint.");
            }
            out.println("ID de la lista obtenido: " + listId);
            out.println("----------------------------------------------------------");
            out.flush();

            int totalEliminados = 0;
            int pases = 0;
            
            out.println("Paso 2/4: Iniciando bucle de eliminación. Esto puede tardar muchos minutos...");
            out.flush();

            while (true) {
                pases++;
                out.println("\n[Pase " + pases + "] Solicitando lote de items desde SharePoint...");
                out.flush();
                
                ListItemCollectionResponse currentPage = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
                
                if (currentPage == null || currentPage.getValue().isEmpty()) {
                    out.println("[Pase " + pases + "] Respuesta vacía. No se encontraron más items.");
                    out.flush();
                    break; // Salimos del bucle while(true)
                }

                List<ListItem> itemsEnPagina = currentPage.getValue();
                out.println("[Pase " + pases + "] Se encontraron " + itemsEnPagina.size() + " items. Procediendo a borrarlos uno por uno...");
                out.flush();

                for (int i = 0; i < itemsEnPagina.size(); i++) {
                    ListItem item = itemsEnPagina.get(i);
                    SharePointUtil.deleteListItem(SharePointUtil.SITE_ID, listId, item.getId());
                    out.print("."); // Imprime un punto por cada item borrado
                    if ((i + 1) % 100 == 0) { // Añade un salto de línea cada 100 puntos
                        out.println();
                    }
                    out.flush();
                    Thread.sleep(PAUSA_CORTA_MS);
                }
                
                totalEliminados += itemsEnPagina.size();
                out.println("\n[Pase " + pases + "] Pase completado. Total eliminados hasta ahora: " + totalEliminados);
                out.flush();
            }
            
            out.println("\n----------------------------------------------------------");
            out.println("Paso 3/4: Verificación final. Comprobando si la lista está realmente vacía...");
            out.flush();
            
            ListItemCollectionResponse finalCheck = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId);
            if(finalCheck != null && !finalCheck.getValue().isEmpty()) {
                 throw new Exception("¡FALLO DE VERIFICACIÓN! La limpieza terminó, pero la lista todavía contiene " + finalCheck.getValue().size() + " items.");
            }
            out.println("Verificación final exitosa. La lista está vacía.");
            out.flush();
            
            String successMessage = "¡ÉXITO! Limpieza profunda completada. Se han eliminado un total de " + totalEliminados + " items.";
            out.println("\n----------------------------------------------------------");
            out.println("Paso 4/4: " + successMessage);
            out.flush();

        } catch (Exception e) {
            out.println("\n\n--- ¡ERROR CRÍTICO DURANTE LA LIMPIEZA! ---");
            out.println("Mensaje del error: " + e.getMessage());
            out.println("\nTraza del error completa:");
            e.printStackTrace(out);
            out.flush();
        }
    }
}
