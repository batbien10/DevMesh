package devmesh.platform;

public final class StreamingProcessFixture {
    public static void main(String[] args) throws Exception {
        System.out.println("line 1");
        System.out.flush();
        Thread.sleep(150);
        System.err.println("error 2");
        System.err.flush();
        Thread.sleep(150);
        System.out.println("line 3");
        System.out.flush();
    }
}